package capstone.task2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.apache.spark.storage.StorageLevel;

import capstone.task2.FlightInformation;
import capstone.task2.FlightInformation.ColumnNames;
import capstone.task2.MapReduceHelper.SummingToUse;
import kafka.serializer.StringDecoder;
import scala.Tuple2;

public class G1T1RankAirports {
	public static void main(String[] args) throws IOException {
		if (args.length < 4) {
			System.exit(1);
		}
		MapReduceHelper.summingToUse = SummingToUse.G1T1;
		
		String className = G1T1RankAirports.class.getSimpleName();
		Map<String, String> paramsMap = new HashMap<String, String>();
		SparkConf sparkConf = new SparkConf().setAppName(className);
		Map<String, Integer> topicMap = new HashMap<String, Integer>();
		
		MapReduceHelper.fillBaseStreamingParams(args, paramsMap, sparkConf, topicMap, className);
		
		JavaStreamingContext jssc = new JavaStreamingContext(sparkConf, new Duration(8000));
		JavaPairReceiverInputDStream<String, String> messages = KafkaUtils.createStream(jssc, String.class, String.class, StringDecoder.class, StringDecoder.class, paramsMap, topicMap, StorageLevel.MEMORY_AND_DISK_SER_2());

		JavaDStream<String> lines = messages.map(MapReduceHelper.getBaseInputPreprocessingFunction());

		JavaPairDStream<String, Integer> sums = lines.flatMapToPair(
				new PairFlatMapFunction<String, String, Integer>() {
					private static final long serialVersionUID = 1L;
					ColumnNames[] columns = new ColumnNames[] { ColumnNames.Origin, ColumnNames.Dest };
		
					public Iterable<Tuple2<String, Integer>> call(String value) throws Exception {
						ArrayList<Tuple2<String, Integer>> list = new ArrayList<Tuple2<String, Integer>>();
		
						if (value == null || value.toString().trim().isEmpty())
							return list;
		
						FlightInformation information = new FlightInformation(
								value.toString().trim(), columns);
		
						String origin = information.GetValues()[0];
						if (!origin.isEmpty())
							list.add(new Tuple2<String, Integer>(origin, 1));
		
						String dest = information.GetValues()[1];
						if (!dest.isEmpty())
							list.add(new Tuple2<String, Integer>(dest, 1));
		
						return list;
					}
				}).reduceByKey(new Function2<Integer, Integer, Integer>() {
						private static final long serialVersionUID = 1L;
			
						public Integer call(Integer value1, Integer value2)
								throws Exception {
							return value1 + value2;
						}
		});
	
		JavaPairDStream<String, Integer> fullRDD = sums.transformToPair(MapReduceHelper.<String, Integer>getRDDJoinWithPreviousFunction());	
		JavaPairDStream<String, Integer> sorted = fullRDD.transformToPair(G1T1RankAirports.getSortFunction());
		
		sorted.print();
		jssc.start();
		jssc.awaitTermination();
		jssc.stop();
	}
	
	public static Function<JavaPairRDD<String, Integer>, JavaPairRDD<String, Integer>> getSortFunction(){
		return new Function<JavaPairRDD<String, Integer>, JavaPairRDD<String, Integer>>() {
			private static final long serialVersionUID = 1L;
			private Boolean isWritten = false;
			private CassandraHelper cassandraHelper = new CassandraHelper();
			
			public JavaPairRDD<String, Integer> call(JavaPairRDD<String, Integer> pairs) throws Exception {
				JavaPairRDD<Integer, String> rdd = pairs.flatMapToPair(MapReduceHelper.<String, Integer>getRDDFlipFunction()).sortByKey(false);
				
				if (!isWritten && MapReduceHelper.flushRDD)
				{
					isWritten = true;
					System.out.println("\n-------WRITE TO CASSANDRA 1------ ");

					String cassandraIp = rdd.context().getConf().get("spark.connection.cassandra.host");
					Integer cassandraPort = Integer.parseInt(rdd.context().getConf().get("spark.cassandra.connection.port"));
					cassandraHelper.createConnection(cassandraIp, cassandraPort);
					
					List<Tuple2<Integer, String>> list = rdd.take(10);
                	cassandraHelper.prepareQueries("INSERT INTO keyspacecapstone.topAirports (airport, popularity, Id, Group) VALUES (?,?,?,?);");
                	
                	Object[] values = new Object[4];
                	Integer i = 0;
                	
                    for (Tuple2<Integer, String> tuple2 : list) {
                    	values[0] = tuple2._2();
                    	values[1] = tuple2._1().toString();
                    	values[2] = i;
                    	values[3] = 0;
                    	i++;
                    	
                    	System.out.println("\n--------CASSANDRA " + tuple2._2() + " " + tuple2._1() + " " + i);
                    	
                    	cassandraHelper.addKey(values);
                    	Thread.sleep(100);
					}
                    
    				cassandraHelper.closeConnection();
                }		
				
				return rdd.flatMapToPair(MapReduceHelper.<Integer, String>getRDDFlipFunction());
			}
		};
	}
}
