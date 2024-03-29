package com.microsoft.example;


import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;

import org.apache.storm.hdfs.bolt.HdfsBolt;
import org.apache.storm.hdfs.bolt.format.DefaultFileNameFormat;
import org.apache.storm.hdfs.bolt.format.DelimitedRecordFormat;
import org.apache.storm.hdfs.bolt.format.FileNameFormat;
import org.apache.storm.hdfs.bolt.format.RecordFormat;
import org.apache.storm.hdfs.bolt.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy.Units;
import org.apache.storm.hdfs.bolt.sync.CountSyncPolicy;
import org.apache.storm.hdfs.bolt.sync.SyncPolicy;

import java.io.FileReader;
import java.util.Properties;

import com.microsoft.eventhubs.spout.EventHubSpout;
import com.microsoft.eventhubs.spout.EventHubSpoutConfig;

public class EventHubReader {
  //Entry point for the topology
  public static void main(String[] args) throws Exception {
    //Read and set configuration
    Properties properties = new Properties();
    //Arguments? Or from config file?
    if(args.length > 1) {
      properties.load(new FileReader(args[1]));
    }
    else {
      properties.load(EventHubReader.class.getClassLoader().getResourceAsStream(
        "EventHubs.properties"));
    }
    //Load configuration from file for Event Hub
    String policyName = properties.getProperty("eventhubs.readerpolicyname");
    String policyKey = properties.getProperty("eventhubs.readerpolicykey");
    String namespaceName = properties.getProperty("eventhubs.namespace");
    String entityPath = properties.getProperty("eventhubs.entitypath");
    String zkEndpointAddress = properties.getProperty("zookeeper.connectionstring");
    int partitionCount = Integer.parseInt(properties.getProperty("eventhubs.partitions.count"));
    int checkpointIntervalInSeconds = Integer.parseInt(properties.getProperty("eventhubs.checkpoint.interval"));
    int receiverCredits = Integer.parseInt(properties.getProperty("eventhubs.receiver.credits"));
    //Create configuration object for the spout
    EventHubSpoutConfig spoutConfig = new EventHubSpoutConfig(policyName, policyKey,
      namespaceName, entityPath, partitionCount, zkEndpointAddress,
      checkpointIntervalInSeconds, receiverCredits);

    //Used to build the topology
    TopologyBuilder builder = new TopologyBuilder();
    //Add the spout, with a name of 'spout'
    //and parallelism hint of 5 executors
    builder.setSpout("eventhubspout", new EventHubSpout(spoutConfig), spoutConfig.getPartitionCount())
      .setNumTasks(spoutConfig.getPartitionCount());

    //Create HdfsBolt to store data to Windows Azure Blob Storage
    SyncPolicy syncPolicy = new CountSyncPolicy(10);
    //Set the size ridiculously small (20kb) for this example
    // so that files get written to the filesystem quicker.
    FileRotationPolicy rotationPolicy = new FileSizeRotationPolicy(10.0f, Units.KB);
    //Rows written are comma delimited and terminated by newline
    RecordFormat recordFormat = new DelimitedRecordFormat().withFieldDelimiter(",");
    //Store the data into the /devicedata directory
    FileNameFormat fileNameFormat = new DefaultFileNameFormat().withPath("/devicedata/");
    HdfsBolt wasbBolt = new HdfsBolt()
      .withFsUrl("wasb:///")
      .withRecordFormat(recordFormat)
      .withFileNameFormat(fileNameFormat)
      .withRotationPolicy(rotationPolicy)
      .withSyncPolicy(syncPolicy);
      
    //Parse the data from the JSON format in the Event Hub into tuples
    builder.setBolt("parserbolt", new ParserBolt(), spoutConfig.getPartitionCount())
      .shuffleGrouping("eventhubspout")
      .setNumTasks(spoutConfig.getPartitionCount());
    //Set the WASB bolt to read from the parser output
    builder.setBolt("wasbbolt", wasbBolt, 10)
      .shuffleGrouping("parserbolt")
      .setNumTasks(spoutConfig.getPartitionCount());

    //new configuration
    Config conf = new Config();
    conf.setDebug(true);

    //If there are arguments, we are running on a cluster
    if (args != null && args.length > 0) {
      //parallelism hint to set the number of workers
      conf.setNumWorkers(spoutConfig.getPartitionCount());
      //submit the topology
      StormSubmitter.submitTopology(args[0], conf, builder.createTopology());
    }
    //Otherwise, we are running locally
    else {
      //Cap the maximum number of executors that can be spawned
      //for a component to 3
      conf.setMaxTaskParallelism(3);
      //LocalCluster is used to run locally
      LocalCluster cluster = new LocalCluster();
      //submit the topology
      cluster.submitTopology("reader", conf, builder.createTopology());
      //sleep
      Thread.sleep(10000);
      //shut down the cluster
      cluster.shutdown();
    }
  }
}
