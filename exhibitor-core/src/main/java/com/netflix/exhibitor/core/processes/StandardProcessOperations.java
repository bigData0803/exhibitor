/*
 *
 *  Copyright 2011 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package com.netflix.exhibitor.core.processes;

import com.google.common.collect.Iterables;
import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.netflix.exhibitor.core.Exhibitor;
import com.netflix.exhibitor.core.activity.ActivityLog;
import com.netflix.exhibitor.core.config.InstanceConfig;
import com.netflix.exhibitor.core.config.IntConfigs;
import com.netflix.exhibitor.core.config.StringConfigs;
import com.netflix.exhibitor.core.state.ServerList;
import com.netflix.exhibitor.core.state.ServerSpec;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.Properties;

public class StandardProcessOperations implements ProcessOperations
{
    private final Exhibitor exhibitor;

    public StandardProcessOperations(Exhibitor exhibitor) throws IOException
    {
        this.exhibitor = exhibitor;
    }

    @Override
    public void cleanupInstance() throws Exception
    {
        Details             details = new Details(exhibitor);
        if ( !details.isValid() )
        {
            return;
        }

        // see http://zookeeper.apache.org/doc/r3.3.3/zookeeperAdmin.html#Ongoing+Data+Directory+Cleanup
        ProcessBuilder      builder = new ProcessBuilder
        (
            "java",
            "-cp",
            String.format("%s:%s:%s", details.zooKeeperJarPath.getPath(), details.log4jJarPath.getPath(), details.configDirectory.getPath()),
            "org.apache.zookeeper.server.PurgeTxnLog",
            details.dataDirectory.getPath(),
            details.dataDirectory.getPath(),
            "-n",
            Integer.toString(exhibitor.getConfigManager().getConfig().getInt(IntConfigs.CLEANUP_MAX_FILES))
        );

        exhibitor.getProcessMonitor().monitor(ProcessTypes.CLEANUP, builder.start(), "Cleanup task completed", ProcessMonitor.Mode.DESTROY_ON_INTERRUPT, ProcessMonitor.Streams.ERROR);
    }

    @Override
    public void killInstance() throws Exception
    {
        exhibitor.getLog().add(ActivityLog.Type.INFO, "Attempting to start/restart ZooKeeper");

        exhibitor.getProcessMonitor().destroy(ProcessTypes.ZOOKEEPER);

        ProcessBuilder          builder = new ProcessBuilder("jps");
        Process                 jpsProcess = builder.start();
        String                  pid = null;
        try
        {
            BufferedReader in = new BufferedReader(new InputStreamReader(jpsProcess.getInputStream()));
            for(;;)
            {
                String  line = in.readLine();
                if ( line == null )
                {
                    break;
                }
                String[]  components = line.split("[ \t]");
                if ( (components.length == 2) && components[1].equals("QuorumPeerMain") )
                {
                    pid = components[0];
                    break;
                }
            }
        }
        finally
        {
            jpsProcess.destroy();
        }

        if ( pid == null )
        {
            exhibitor.getLog().add(ActivityLog.Type.INFO, "jps didn't find instance - assuming ZK is not running");
        }
        else
        {
            builder = new ProcessBuilder("kill", "-9", pid);
            try
            {
                int     result = builder.start().waitFor();
                exhibitor.getLog().add(ActivityLog.Type.INFO, "Kill attempted result: " + result);
            }
            catch ( InterruptedException e )
            {
                // don't reset thread interrupted status

                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Process interrupted while running: kill -9 " + pid);
                throw e;
            }
        }
    }

    @Override
    public void startInstance() throws Exception
    {
        Details         details = new Details(exhibitor);
        String          javaEnvironmentScript = exhibitor.getConfigManager().getConfig().getString(StringConfigs.JAVA_ENVIRONMENT);

        prepConfigFile(details);
        if ( (javaEnvironmentScript != null) && (javaEnvironmentScript.trim().length() > 0) )
        {
            File     envFile = new File(details.configDirectory, "java.env");
            Files.write(javaEnvironmentScript, envFile, Charset.defaultCharset());
        }

        File            binDirectory = new File(details.zooKeeperDirectory, "bin");
        File            startScript = new File(binDirectory, "zkServer.sh");
        ProcessBuilder  builder = new ProcessBuilder(startScript.getPath(), "start").directory(binDirectory.getParentFile());

        exhibitor.getProcessMonitor().monitor(ProcessTypes.ZOOKEEPER, builder.start(), null, ProcessMonitor.Mode.LEAVE_RUNNING_ON_INTERRUPT, ProcessMonitor.Streams.BOTH);

        exhibitor.getLog().add(ActivityLog.Type.INFO, "Process started via: " + startScript.getPath());
    }

    private void prepConfigFile(Details details) throws IOException
    {
        InstanceConfig          config = exhibitor.getConfigManager().getConfig();

        ServerList serverList = new ServerList(config.getString(StringConfigs.SERVERS_SPEC));

        File                    idFile = new File(details.dataDirectory, "myid");
        ServerSpec us = Iterables.find(serverList.getSpecs(), ServerList.isUs(exhibitor.getThisJVMHostname()), null);
        if ( us != null )
        {
            Files.createParentDirs(idFile);
            String                  id = String.format("%d\n", us.getServerId());
            Files.write(id.getBytes(), idFile);
        }
        else
        {
            exhibitor.getLog().add(ActivityLog.Type.INFO, "Starting in standalone mode");
            if ( idFile.exists() && !idFile.delete() )
            {
                exhibitor.getLog().add(ActivityLog.Type.ERROR, "Could not delete ID file: " + idFile);
            }
        }

        Properties      localProperties = new Properties();
        localProperties.putAll(details.properties);

        localProperties.setProperty("clientPort", Integer.toString(config.getInt(IntConfigs.CLIENT_PORT)));

        String          portSpec = String.format(":%d:%d", config.getInt(IntConfigs.CONNECT_PORT), config.getInt(IntConfigs.ELECTION_PORT));
        for ( ServerSpec spec : serverList.getSpecs() )
        {
            localProperties.setProperty("server." + spec.getServerId(), spec.getHostname() + portSpec + spec.getServerType().getZookeeperConfigValue());
        }

        File            configFile = new File(details.configDirectory, "zoo.cfg");
        OutputStream out = new BufferedOutputStream(new FileOutputStream(configFile));
        try
        {
            localProperties.store(out, "Auto-generated by Exhibitor - " + new Date());
        }
        finally
        {
            Closeables.closeQuietly(out);
        }
    }
}
