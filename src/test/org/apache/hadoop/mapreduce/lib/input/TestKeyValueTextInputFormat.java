/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.lib.input;

import java.io.*;
import java.util.*;
import junit.framework.TestCase;

import org.apache.commons.logging.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.io.compress.*;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.util.LineReader;
import org.apache.hadoop.util.ReflectionUtils;

public class TestKeyValueTextInputFormat extends TestCase {
  private static final Log LOG =
    LogFactory.getLog(TestKeyValueTextInputFormat.class.getName());

  private static int MAX_LENGTH = 10000;
  
  private static Configuration defaultConf = new Configuration();
  private static FileSystem localFs = null; 
  static {
    try {
      localFs = FileSystem.getLocal(defaultConf);
    } catch (IOException e) {
      throw new RuntimeException("init failure", e);
    }
  }
  private static Path workDir = 
    new Path(new Path(System.getProperty("test.build.data", "."), "data"),
             "TestKeyValueTextInputFormat");
  
  public void testFormat() throws Exception {
    Job job = new Job(defaultConf);
    Path file = new Path(workDir, "test.txt");

    int seed = new Random().nextInt();
    LOG.info("seed = "+seed);
    Random random = new Random(seed);

    localFs.delete(workDir, true);
    FileInputFormat.setInputPaths(job, workDir);

    // for a variety of lengths
    for (int length = 0; length < MAX_LENGTH;
         length+= random.nextInt(MAX_LENGTH/10)+1) {

      LOG.debug("creating; entries = " + length);

      // create a file with length entries
      Writer writer = new OutputStreamWriter(localFs.create(file));
      try {
        for (int i = 0; i < length; i++) {
          writer.write(Integer.toString(i*2));
          writer.write("\t");
          writer.write(Integer.toString(i));
          writer.write("\n");
        }
      } finally {
        writer.close();
      }

      KeyValueTextInputFormat format = new KeyValueTextInputFormat();
      JobContext jobContext = new JobContext(job.getConfiguration(), new JobID());
      List<InputSplit> splits = format.getSplits(jobContext);
      LOG.debug("splitting: got =        " + splits.size());
      
      TaskAttemptContext context = new TaskAttemptContext(job.getConfiguration(), new TaskAttemptID());

      // check each split
      BitSet bits = new BitSet(length);
      for (InputSplit split : splits) {
        LOG.debug("split= " + split);
        RecordReader<Text, Text> reader =
          format.createRecordReader(split, context);
        Class readerClass = reader.getClass();
        assertEquals("reader class is KeyValueLineRecordReader.", KeyValueLineRecordReader.class, readerClass);        

        reader.initialize(split, context);
        try {
          int count = 0;
          while (reader.nextKeyValue()) {
            int v = Integer.parseInt(reader.getCurrentValue().toString());
            LOG.debug("read " + v);
            if (bits.get(v)) {
              LOG.warn("conflict with " + v + 
                       " in split " + split +
                       " at "+reader.getProgress());
            }
            assertFalse("Key in multiple partitions.", bits.get(v));
            bits.set(v);
            count++;
          }
          LOG.debug("split="+split+" count=" + count);
        } finally {
          reader.close();
        }
      }
      assertEquals("Some keys in no partition.", length, bits.cardinality());

    }
  }
  private LineReader makeStream(String str) throws IOException {
    return new LineReader(new ByteArrayInputStream
                                           (str.getBytes("UTF-8")), 
                                           defaultConf);
  }
  
  public void testUTF8() throws Exception {
    LineReader in = makeStream("abcd\u20acbdcd\u20ac");
    Text line = new Text();
    in.readLine(line);
    assertEquals("readLine changed utf8 characters", 
                 "abcd\u20acbdcd\u20ac", line.toString());
    in = makeStream("abc\u200axyz");
    in.readLine(line);
    assertEquals("split on fake newline", "abc\u200axyz", line.toString());
  }

  public void testNewLines() throws Exception {
    LineReader in = makeStream("a\nbb\n\nccc\rdddd\r\neeeee");
    Text out = new Text();
    in.readLine(out);
    assertEquals("line1 length", 1, out.getLength());
    in.readLine(out);
    assertEquals("line2 length", 2, out.getLength());
    in.readLine(out);
    assertEquals("line3 length", 0, out.getLength());
    in.readLine(out);
    assertEquals("line4 length", 3, out.getLength());
    in.readLine(out);
    assertEquals("line5 length", 4, out.getLength());
    in.readLine(out);
    assertEquals("line5 length", 5, out.getLength());
    assertEquals("end of file", 0, in.readLine(out));
  }
  
  private static void writeFile(FileSystem fs, Path name, 
                                CompressionCodec codec,
                                String contents) throws IOException {
    OutputStream stm;
    if (codec == null) {
      stm = fs.create(name);
    } else {
      stm = codec.createOutputStream(fs.create(name));
    }
    stm.write(contents.getBytes());
    stm.close();
  }
  
  private static List<Text> readSplit(KeyValueTextInputFormat format, 
                                      InputSplit split, 
                                      TaskAttemptContext context) throws IOException, InterruptedException {
    List<Text> result = new ArrayList<Text>();
    RecordReader<Text, Text> reader = format.createRecordReader(split, context);
    reader.initialize(split, context);
    while (reader.nextKeyValue()) {
      result.add(new Text(reader.getCurrentValue()));
    }
    return result;
  }
  
  /**
   * Test using the gzip codec for reading
   */
  public static void testGzip() throws Exception {
    Job job = Job.getInstance();
    CompressionCodec gzip = new GzipCodec();
    ReflectionUtils.setConf(gzip, job.getConfiguration());
    localFs.delete(workDir, true);
    writeFile(localFs, new Path(workDir, "part1.txt.gz"), gzip, 
              "line-1\tthe quick\nline-2\tbrown\nline-3\tfox jumped\nline-4\tover\nline-5\t the lazy\nline-6\t dog\n");
    writeFile(localFs, new Path(workDir, "part2.txt.gz"), gzip,
              "line-1\tthis is a test\nline-1\tof gzip\n");
    FileInputFormat.setInputPaths(job, workDir);
    
    KeyValueTextInputFormat format = new KeyValueTextInputFormat();
    JobContext jobContext = new JobContext(job.getConfiguration(), new JobID());
    TaskAttemptContext context = new TaskAttemptContext(job.getConfiguration(), new TaskAttemptID());
    List<InputSplit> splits = format.getSplits(jobContext);
    assertEquals("compressed splits == 2", 2, splits.size());
    FileSplit tmp = (FileSplit) splits.get(0);
    if (tmp.getPath().getName().equals("part2.txt.gz")) {
      splits.set(0, splits.get(1));
      splits.set(1, tmp);
    }
    List<Text> results = readSplit(format, splits.get(0), context);
    assertEquals("splits[0] length", 6, results.size());
    assertEquals("splits[0][5]", " dog", results.get(5).toString());
    results = readSplit(format, splits.get(1), context);
    assertEquals("splits[1] length", 2, results.size());
    assertEquals("splits[1][0]", "this is a test", 
                 results.get(0).toString());    
    assertEquals("splits[1][1]", "of gzip", 
                 results.get(1).toString());    
  }
  
  public static void main(String[] args) throws Exception {
    new TestKeyValueTextInputFormat().testFormat();
  }
}
