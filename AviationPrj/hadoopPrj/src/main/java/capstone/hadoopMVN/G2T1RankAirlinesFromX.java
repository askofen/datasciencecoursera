package capstone.hadoopMVN;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import capstone.hadoopMVN.MapReduceHelper.Pair;
import capstone.hadoopMVN.MapReduceHelper.TextArrayWritable;
import capstone.hadoopMVN.FlightInformation.ColumnNames;

public class G2T1RankAirlinesFromX extends Configured implements Tool {
	public static void main(String[] args) throws Exception {
		int res = ToolRunner.run(new Configuration(), new G2T1RankAirlinesFromX(), args);
		System.exit(res);
	}
	
    public int run(String[] args) throws Exception {
        Configuration conf = this.getConf();
        conf.set("destFileName", args[1]);
        FileSystem fs = FileSystem.get(conf);
        Path tmpPath = new Path("/Capstone/Tmp/TopAirlinesFromX");
        fs.delete(tmpPath, true);

        Job jobA = Job.getInstance(conf, "Airlines Count");
        jobA.setOutputKeyClass(Text.class);
        jobA.setOutputValueClass(DoubleWritable.class);

        jobA.setMapOutputKeyClass(Text.class);
        jobA.setMapOutputValueClass(IntWritable.class);
        
        jobA.setMapperClass(AirlinesCountMap.class);
        jobA.setReducerClass(AirlinesCountReduce.class);

        String filesStr = MapReduceHelper.readHDFSFile(args[0], conf);
        String[] files = filesStr.split("\n");
        
        for (int i = 0; i < files.length; i++) {
			String inputName = files[i];
			
			if (inputName != null && !inputName.trim().isEmpty())
				FileInputFormat.addInputPath(jobA, new Path(inputName.trim()));
		}
        
        FileOutputFormat.setOutputPath(jobA, tmpPath);
        
        jobA.setJarByClass(G2T1RankAirlinesFromX.class);
        jobA.waitForCompletion(true);
        
        Job jobB = Job.getInstance(conf, "Rank Airlines Count");
        jobB.setOutputKeyClass(Text.class);
        jobB.setOutputValueClass(DoubleWritable.class);

        jobB.setMapOutputKeyClass(Text.class);
        jobB.setMapOutputValueClass(TextArrayWritable.class);

        jobB.setMapperClass(TopAirlinesMap.class);
        jobB.setReducerClass(TopAirlinesReduce.class);

        Path outputPath = new Path("/Capstone/Output/TopAirlinesFromX");
        fs.delete(outputPath, true);
        
        FileInputFormat.setInputPaths(jobB, tmpPath);
        FileOutputFormat.setOutputPath(jobB, outputPath);

        jobB.setInputFormatClass(KeyValueTextInputFormat.class);
        jobB.setOutputFormatClass(TextOutputFormat.class);

        jobB.setJarByClass(G2T1RankAirlinesFromX.class);
        return jobB.waitForCompletion(true) ? 0 : 1;
    }
 
    public static class AirlinesCountMap extends Mapper<Object, Text, Text, IntWritable> {
    	ColumnNames[] columns = new ColumnNames[] { ColumnNames.Origin, ColumnNames.AirlineID, ColumnNames.DepDelayed, ColumnNames.AirlineCode };

    	List<String> airports;

		@Override
		protected void setup(Context context) throws IOException,InterruptedException {
			Configuration conf = context.getConfiguration();
			String destFileName = conf.get("destFileName");
			airports = Arrays.asList(MapReduceHelper.readHDFSFile(destFileName, conf).split("\n"));
		}
    	
        @Override
        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            if (value == null || value.toString().trim().isEmpty())
            	return;
            
            FlightInformation information = new FlightInformation(value.toString().trim(), columns);
            
			String origin = information.GetValues()[0];		
			
			if (origin.isEmpty() || !airports.contains(origin))
				return;

			String depDelayed = information.GetValue(ColumnNames.DepDelayed);		
			String airlineID = information.GetValue(ColumnNames.AirlineID);		
			
			if (depDelayed.isEmpty() || airlineID.isEmpty())
				return;
			
			Integer depDelayedVal = Integer.parseInt(depDelayed);
			context.write(new Text(origin + "_" + airlineID), new IntWritable(depDelayedVal));
        }
    }

    public static class AirlinesCountReduce extends Reducer<Text, IntWritable, Text, DoubleWritable> {    	
        @Override
        public void reduce(Text key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException {
        	int sum = 0;
        	int valuesCount = 0;
        	
        	for (IntWritable val : values) {
        		sum += val.get();
        		valuesCount++;
        	}
        	
        	double perf = 1;
        	double inTime = valuesCount - sum;
        	
        	if (valuesCount != 0)
        		perf = inTime / valuesCount;
        	
        	System.out.println("Origin_airline = " + key.toString() + " valuesCount = " + valuesCount + " sum = " + sum + " perf = " + perf);
        	context.write(key, new DoubleWritable(perf));
        }
    }
    
    public static class TopAirlinesMap extends Mapper<Text, Text, Text, TextArrayWritable> {
        @Override
        public void map(Text key, Text value, Context context) throws IOException, InterruptedException {
        	Double perf = Double.parseDouble(value.toString());
        	String[] strings = key.toString().split("_");
    		
        	String origin = strings[0];
        	String airlineId = strings[1];
        	
        	TextArrayWritable val = new TextArrayWritable(new String[] {airlineId, perf.toString()});
    		context.write(new Text(origin), val);
        }
    }

    public static class TopAirlinesReduce extends Reducer<Text, TextArrayWritable, Text, DoubleWritable> {
        Integer N = 10;

        @Override
        public void reduce(Text key, Iterable<TextArrayWritable> values, Context context) throws IOException, InterruptedException {
        	TreeSet<Pair<Double, String>> countToAirportsMap = new TreeSet<Pair<Double, String>>();
    		String origin = key.toString();
        	
        	for (TextArrayWritable val: values) {
        		Text[] pair = (Text[]) val.toArray();
        		
        		String airlineId = pair[0].toString();
        		Double perf = Double.parseDouble(pair[1].toString());
        		
        		countToAirportsMap.add(new Pair<Double, String>(perf, airlineId));

        		if (countToAirportsMap.size() > N) {
        			countToAirportsMap.remove(countToAirportsMap.first());
        		}
        	}
        	
        	Iterator<Pair<Double, String>> iterator = countToAirportsMap.descendingIterator();
        	
        	while(iterator.hasNext())
            {
        		Pair<Double, String> item = iterator.next();
        		String airline = new String(item.second);
        		DoubleWritable value = new DoubleWritable(item.first);
        		context.write(new Text(origin + ": " + airline), value);
            }
        }
    }
}
