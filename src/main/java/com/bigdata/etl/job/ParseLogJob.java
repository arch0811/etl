package com.bigdata.etl.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bigdata.etl.mr.*;
import com.bigdata.etl.utils.IPUtil;
import com.hadoop.compression.lzo.LzopCodec;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Map;



public class ParseLogJob extends Configured implements Tool {

    public static LogGenericWritable parseLog(String row) throws ParseException {
        String[] logPart = StringUtils.split(row,"\u1111");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        long timeTag = dateFormat.parse(logPart[0]).getTime();
        String activeName=logPart[1];
        JSONObject bizData= JSON.parseObject(logPart[2]);

        LogGenericWritable logData=new LogWritable();
        logData.put("time_tag",new LogFieldWritable(timeTag));
        logData.put("active_name",new LogFieldWritable(activeName));

        for (Map.Entry<String,Object> entry:bizData.entrySet()){
            logData.put(entry.getKey(),new LogFieldWritable(entry.getValue()));
        }

        return  logData;

    }

    public static class LogWritable extends LogGenericWritable{

        protected String[] getFieldName(){
            return new String[] {"active_name","session_id","time_tag","ip","device_id","req_url","user_id","product_id","order_id",};
        }
    }

    public static class LogMapper extends Mapper<LongWritable, Text, TextLongWritable,LogGenericWritable> {
        public void map(LongWritable key,Text value,Context context) throws IOException,InterruptedException{
            try{
                LogGenericWritable parsedLog = parseLog(value.toString());
                String session = (String) parsedLog.getObject("session_id");
                Long timeTag = (Long) parsedLog.getObject("time_tag");

                TextLongWritable outKey = new TextLongWritable();
                outKey.setText(new Text(session));
                outKey.setCompareValue(new LongWritable(timeTag));

                context.write(outKey,parsedLog);
            } catch (ParseException e) {
                e.printStackTrace();
            }

        }
    }

    public static class LogReducer extends Reducer<TextLongWritable,LogGenericWritable,NullWritable,Text>{

        private Text sessionId;
        private JSONArray actionPath = new JSONArray();

        public void setup(Context context) throws IOException, InterruptedException{
            IPUtil.load("17monipdb.dat");
        }
        public void reduce(TextLongWritable key,Iterable<LogGenericWritable>values,Context context) throws IOException, InterruptedException {
            Text sid=key.getText();
            if (sessionId == null || !sid.equals(sessionId)){
                sessionId = new Text(sid);
                actionPath.clear();
            }

            for (LogGenericWritable v : values){
                String ip = (String)v.getObject("ip");
                String[]address = IPUtil.find(ip);
                JSONObject addr = new JSONObject();
                addr.put("country",address[0]);
                addr.put("province",address[1]);
                addr.put("city",address[2]);

                String activeName = (String)v.getObject("active_name");
                String reqUrl = (String)v.getObject("req_url");
                //如果activename是pageview就取req_url作为用户路径中的单元，如果是其它的，就直接以activename作为单元
                String pathUnit = "pageview".equals(activeName) ? reqUrl : activeName;
                actionPath.add(pathUnit);

                JSONObject datum = JSON.parseObject(v.asJsonString());
                datum.put("address",addr);
                datum.put("action_path",actionPath);

                context.write(null, new Text(datum.toJSONString()));
            }
        }
    }

    public int run (String[] args) throws Exception {
        Configuration config = getConf();
        config.addResource("mr.xml");
        Job job =Job.getInstance(config);
        job.setJarByClass(ParseLogJob.class);
        job.setJobName("parselog");
        job.setMapperClass(LogMapper.class);
        job.setReducerClass(LogReducer.class);
        job.setMapOutputKeyClass(TextLongWritable.class);//Map输出类型改变
        //增加两个参数
        job.setGroupingComparatorClass(TextLongGroupComparator.class);
        job.setPartitionerClass(TextLongPartitioner.class);
        job.setMapOutputValueClass(LogWritable.class);
        job.setOutputValueClass(Text.class);
        //改成分布式缓存
        job.addCacheFile(new URI(config.get("ip.file.path")));

        FileInputFormat.addInputPath(job,new Path(args[0]));
        Path outputPath= new Path(args[1]);
        FileOutputFormat.setOutputPath(job,outputPath);
        /*FileOutputFormat.setCompressOutput(job,true);
        FileOutputFormat.setOutputCompressorClass(job, LzopCodec.class);//压缩文件*/


        FileSystem fs = FileSystem.get(config);
        if (fs.exists(outputPath)){
            fs.delete(outputPath,true);
        }

        if (!job.waitForCompletion(true)){
            throw new RuntimeException(job.getJobName()+" failed!");
        }

        return 0;
    }

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(),new ParseLogJob(),args);
        System.exit(res);

    }

}

