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

package org.apache.hadoop.hdfs;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileSystem.Statistics;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClient.DFSDataInputStream;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.namenode.DatanodeDescriptor;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.ShellBasedUnixGroupsMapping;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;

/** Utilities for HDFS tests */
public class DFSTestUtil {
  
  private static Random gen = new Random();
  private static String[] dirNames = {
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"
  };
  
  private int maxLevels;// = 3;
  private int maxSize;// = 8*1024;
  private int nFiles;
  private MyFile[] files;
  
  /** Creates a new instance of DFSTestUtil
   *
   * @param testName Name of the test from where this utility is used
   * @param nFiles Number of files to be created
   * @param maxLevels Maximum number of directory levels
   * @param maxSize Maximum size for file
   */
  public DFSTestUtil(String testName, int nFiles, int maxLevels, int maxSize) {
    this.nFiles = nFiles;
    this.maxLevels = maxLevels;
    this.maxSize = maxSize;
  }
  
  /** class MyFile contains enough information to recreate the contents of
   * a single file.
   */
  private class MyFile {
    
    private String name = "";
    private int size;
    private long seed;
    
    MyFile() {
      int nLevels = gen.nextInt(maxLevels);
      if (nLevels != 0) {
        int[] levels = new int[nLevels];
        for (int idx = 0; idx < nLevels; idx++) {
          levels[idx] = gen.nextInt(10);
        }
        StringBuffer sb = new StringBuffer();
        for (int idx = 0; idx < nLevels; idx++) {
          sb.append(dirNames[levels[idx]]);
          sb.append("/");
        }
        name = sb.toString();
      }
      long fidx = -1;
      while (fidx < 0) { fidx = gen.nextLong(); }
      name = name + Long.toString(fidx);
      size = gen.nextInt(maxSize);
      seed = gen.nextLong();
    }
    
    String getName() { return name; }
    int getSize() { return size; }
    long getSeed() { return seed; }
  }

  public void createFiles(FileSystem fs, String topdir) throws IOException {
    createFiles(fs, topdir, (short)3);
  }
  
  /** create nFiles with random names and directory hierarchies
   *  with random (but reproducible) data in them.
   */
  void createFiles(FileSystem fs, String topdir,
                   short replicationFactor) throws IOException {
    files = new MyFile[nFiles];
    
    for (int idx = 0; idx < nFiles; idx++) {
      files[idx] = new MyFile();
    }
    
    Path root = new Path(topdir);
    
    for (int idx = 0; idx < nFiles; idx++) {
      createFile(fs, new Path(root, files[idx].getName()), files[idx].getSize(),
          replicationFactor, files[idx].getSeed());
    }
  }
  
  public static void createFile(FileSystem fs, Path fileName, long fileLen, 
      short replFactor, long seed) throws IOException {
    if (!fs.mkdirs(fileName.getParent())) {
      throw new IOException("Mkdirs failed to create " + 
                            fileName.getParent().toString());
    }
    FSDataOutputStream out = null;
    try {
      out = fs.create(fileName, replFactor);
      byte[] toWrite = new byte[1024];
      Random rb = new Random(seed);
      long bytesToWrite = fileLen;
      while (bytesToWrite>0) {
        rb.nextBytes(toWrite);
        int bytesToWriteNext = (1024<bytesToWrite)?1024:(int)bytesToWrite;

        out.write(toWrite, 0, bytesToWriteNext);
        bytesToWrite -= bytesToWriteNext;
      }
      out.close();
      out = null;
    } finally {
      IOUtils.closeStream(out);
    }
  }
  
  /** check if the files have been copied correctly. */
  public boolean checkFiles(FileSystem fs, String topdir) throws IOException {
    
    //Configuration conf = new Configuration();
    Path root = new Path(topdir);
    
    for (int idx = 0; idx < nFiles; idx++) {
      Path fPath = new Path(root, files[idx].getName());
      FSDataInputStream in = fs.open(fPath);
      byte[] toRead = new byte[files[idx].getSize()];
      byte[] toCompare = new byte[files[idx].getSize()];
      Random rb = new Random(files[idx].getSeed());
      rb.nextBytes(toCompare);
      in.readFully(0, toRead);
      in.close();
      for (int i = 0; i < toRead.length; i++) {
        if (toRead[i] != toCompare[i]) {
          return false;
        }
      }
      toRead = null;
      toCompare = null;
    }
    
    return true;
  }

  void setReplication(FileSystem fs, String topdir, short value) 
                                              throws IOException {
    Path root = new Path(topdir);
    for (int idx = 0; idx < nFiles; idx++) {
      Path fPath = new Path(root, files[idx].getName());
      fs.setReplication(fPath, value);
    }
  }

  // waits for the replication factor of all files to reach the
  // specified target
  //
  public void waitReplication(FileSystem fs, String topdir, short value) 
                                              throws IOException {
    Path root = new Path(topdir);

    /** wait for the replication factor to settle down */
    for (int idx = 0; idx < nFiles; idx++) {
      waitReplication(fs, new Path(root, files[idx].getName()), value);
    }
  }

  /** return list of filenames created as part of createFiles */
  public String[] getFileNames(String topDir) {
    if (nFiles == 0)
      return new String[]{};
    else {
      String[] fileNames =  new String[nFiles];
      for (int idx=0; idx < nFiles; idx++) {
        fileNames[idx] = topDir + "/" + files[idx].getName();
      }
      return fileNames;
    }
  }
  
  /** wait for the file's replication to be done */
  public static void waitReplication(FileSystem fs, Path fileName, 
      short replFactor)  throws IOException {
    boolean good;
    do {
      good = true;
      BlockLocation locs[] = fs.getFileBlockLocations(
        fs.getFileStatus(fileName), 0, Long.MAX_VALUE);
      for (int j = 0; j < locs.length; j++) {
        String[] loc = locs[j].getHosts();
        if (loc.length != replFactor) {
          System.out.println("File " + fileName + " has replication factor " +
              loc.length);
          good = false;
          try {
            System.out.println("Waiting for replication factor to drain");
            Thread.sleep(100);
          } catch (InterruptedException e) {} 
          break;
        }
      }
    } while(!good);
  }
  
  /** delete directory and everything underneath it.*/
  public void cleanup(FileSystem fs, String topdir) throws IOException {
    Path root = new Path(topdir);
    fs.delete(root, true);
    files = null;
  }
  
  public static Block getFirstBlock(FileSystem fs, Path path) throws IOException {
    DFSDataInputStream in = 
      (DFSDataInputStream) ((DistributedFileSystem)fs).open(path);
    in.readByte();
    return in.getCurrentBlock();
  }  

  public static List<LocatedBlock> getAllBlocks(FSDataInputStream in)
      throws IOException {
    return ((DFSClient.DFSDataInputStream) in).getAllBlocks();
  }

  public static Token<BlockTokenIdentifier> getAccessToken(FSDataOutputStream out) {
    return ((DFSClient.DFSOutputStream) out.getWrappedStream()).getAccessToken();
  }

  static void setLogLevel2All(org.apache.commons.logging.Log log) {
    ((org.apache.commons.logging.impl.Log4JLogger)log
        ).getLogger().setLevel(org.apache.log4j.Level.ALL);
  }

  static String readFile(File f) throws IOException {
    StringBuilder b = new StringBuilder();
    BufferedReader in = new BufferedReader(new FileReader(f));
    for(int c; (c = in.read()) != -1; b.append((char)c));
    in.close();      
    return b.toString();
  }

  // Returns url content as string.
  public static String urlGet(URL url) throws IOException {
    URLConnection conn = url.openConnection();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    IOUtils.copyBytes(conn.getInputStream(), out, 4096, true);
    return out.toString();
  }

  /**
   * mock class to get group mapping for fake users
   * 
   */
  static class MockUnixGroupsMapping extends ShellBasedUnixGroupsMapping {
    static Map<String, String []> fakeUser2GroupsMap;
    private static final List<String> defaultGroups;
    static {
      defaultGroups = new ArrayList<String>(1);
      defaultGroups.add("supergroup");
      fakeUser2GroupsMap = new HashMap<String, String[]>();
    }
  
    @Override
    public List<String> getGroups(String user) throws IOException {
      boolean found = false;
      
      // check to see if this is one of fake users
      List<String> l = new ArrayList<String>();
      for(String u : fakeUser2GroupsMap.keySet()) {  
        if(user.equals(u)) {
          found = true;
          for(String gr : fakeUser2GroupsMap.get(u)) {
            l.add(gr);
          }
        }
      }
      
      // default
      if(!found) {
        l =  super.getGroups(user);
        if(l.size() == 0) {
          System.out.println("failed to get real group for " + user + 
              "; using default");
          return defaultGroups;
        }
      }
      return l;
    }
  }
  
  /**
   * update the configuration with fake class for mapping user to groups
   * @param conf
   * @param map - user to groups mapping
   */
  static public void updateConfWithFakeGroupMapping
    (Configuration conf, Map<String, String []> map) {
    if(map!=null) {
      MockUnixGroupsMapping.fakeUser2GroupsMap = map;
    }
    
    // fake mapping user to groups
    conf.setClass("hadoop.security.group.mapping",
        DFSTestUtil.MockUnixGroupsMapping.class,
        ShellBasedUnixGroupsMapping.class);
    
  }
  /** // TODO: JGH Reformat this damn code
   *    * Get a FileSystem instance as specified user in a doAs block.
   */
  static public FileSystem getFileSystemAs(UserGroupInformation ugi, 
      final Configuration conf) throws IOException, 
                InterruptedException {
                  return ugi.doAs(new PrivilegedExceptionAction<FileSystem>() {
                    @Override
                    public FileSystem run() throws Exception {
                      return FileSystem.get(conf);
                    }
                  });
  }  

  public static Statistics getStatistics(FileSystem fs) {
    return FileSystem.getStatistics(fs.getUri().getScheme(), fs.getClass());
  }

  public static byte[] generateSequentialBytes(int start, int length) {
    byte[] result = new byte[length];

    for (int i = 0; i < length; i++) {
      result[i] = (byte)((start + i) % 127);
    }

    return result;
  }  
  
  public static DatanodeDescriptor getDatanodeDescriptor(String ipAddr,
      String rackLocation) {
    return new DatanodeDescriptor(new DatanodeID(ipAddr), rackLocation);
  }
}
