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
package org.apache.hadoop.fs;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.security.PrivilegedExceptionAction;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.*;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.MultipleIOException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;

/****************************************************************
 * An abstract base class for a fairly generic filesystem.  It
 * may be implemented as a distributed filesystem, or as a "local"
 * one that reflects the locally-connected disk.  The local version
 * exists for small Hadoop instances and for testing.
 *
 * <p>
 *
 * All user code that may potentially use the Hadoop Distributed
 * File System should be written to use a FileSystem object.  The
 * Hadoop DFS is a multi-machine system that appears as a single
 * disk.  It's useful because of its fault tolerance and potentially
 * very large capacity.
 * 
 * <p>
 * The local implementation is {@link LocalFileSystem} and distributed
 * implementation is DistributedFileSystem.
 *****************************************************************/
public abstract class FileSystem extends Configured implements Closeable {
  public static final String FS_DEFAULT_NAME_KEY = "fs.default.name";

  public static final Log LOG = LogFactory.getLog(FileSystem.class);

  /** FileSystem cache */
  private static final Cache CACHE = new Cache();

  /** The key this instance is stored under in the cache. */
  private Cache.Key key;

  /** Recording statistics per a FileSystem class */
  private static final Map<Class<? extends FileSystem>, Statistics> 
    statisticsTable =
      new IdentityHashMap<Class<? extends FileSystem>, Statistics>();
  
  /**
   * The statistics for this file system.
   */
  protected Statistics statistics;

  /**
   * A cache of files that should be deleted when filsystem is closed
   * or the JVM is exited.
   */
  private Set<Path> deleteOnExit = new TreeSet<Path>();

  /**
   * This method adds a file system for testing so that we can find it later.
   * It is only for testing.
   * @param uri the uri to store it under
   * @param conf the configuration to store it under
   * @param fs the file system to store
   * @throws IOException
   */
  public static void addFileSystemForTesting(URI uri, Configuration conf, 
                                      FileSystem fs) throws IOException {
    CACHE.map.put(new Cache.Key(uri, conf), fs);
  }

  public static FileSystem get(final URI uri, final Configuration conf, 
      final String user)
  throws IOException, InterruptedException {
    UserGroupInformation ugi;
    if (user == null) {
      ugi = UserGroupInformation.getCurrentUser();
    } else {
      ugi = UserGroupInformation.createRemoteUser(user);
    }
    return ugi.doAs(new PrivilegedExceptionAction<FileSystem>() {
      public FileSystem run() throws IOException {
        return get(uri, conf);
      }
    });
  }
  /** Returns the configured filesystem implementation.*/
  public static FileSystem get(Configuration conf) throws IOException {
    return get(getDefaultUri(conf), conf);
  }
  
  /** Get the default filesystem URI from a configuration.
   * @param conf the configuration to access
   * @return the uri of the default filesystem
   */
  public static URI getDefaultUri(Configuration conf) {
    return URI.create(fixName(conf.get(FS_DEFAULT_NAME_KEY, "file:///")));
  }

  /** Set the default filesystem URI in a configuration.
   * @param conf the configuration to alter
   * @param uri the new default filesystem uri
   */
  public static void setDefaultUri(Configuration conf, URI uri) {
    conf.set(FS_DEFAULT_NAME_KEY, uri.toString());
  }

  /** Set the default filesystem URI in a configuration.
   * @param conf the configuration to alter
   * @param uri the new default filesystem uri
   */
  public static void setDefaultUri(Configuration conf, String uri) {
    setDefaultUri(conf, URI.create(fixName(uri)));
  }

  /** Called after a new FileSystem instance is constructed.
   * @param name a uri whose authority section names the host, port, etc.
   *   for this FileSystem
   * @param conf the configuration
   */
  public void initialize(URI name, Configuration conf) throws IOException {
    statistics = getStatistics(name.getScheme(), getClass());    
  }

  /** Returns a URI whose scheme and authority identify this FileSystem.*/
  public abstract URI getUri();
  
  /**
   * Resolve the uri's hostname and add the default port if not in the uri
   * @return URI
   * @see NetUtils#getCanonicalUri(URI, int)
   */
  protected URI getCanonicalUri() {
    return NetUtils.getCanonicalUri(getUri(), getDefaultPort());
  }
  
  /**
   * Get the default port for this file system.
   * @return the default port or 0 if there isn't one
   */
  protected int getDefaultPort() {
    return 0;
  }

  /**
   * Get a canonical service name for this file system.  The token cache is
   * the only user of this value, and uses it to lookup this filesystem's
   * service tokens.  The token cache will not attempt to acquire tokens if the
   * service is null.
   * @return a service string that uniquely identifies this file system, null
   *         if the filesystem does not implement tokens
   * @see SecurityUtil#buildDTServiceName(URI, int) 
   */
  public String getCanonicalServiceName() {
    return SecurityUtil.buildDTServiceName(getUri(), getDefaultPort());
  }
  
  /** @deprecated call #getUri() instead.*/
  public String getName() { return getUri().toString(); }

  /** @deprecated call #get(URI,Configuration) instead. */
  public static FileSystem getNamed(String name, Configuration conf)
    throws IOException {
    return get(URI.create(fixName(name)), conf);
  }

  /** Update old-format filesystem names, for back-compatibility.  This should
   * eventually be replaced with a checkName() method that throws an exception
   * for old-format names. */ 
  private static String fixName(String name) {
    // convert old-format name to new-format name
    if (name.equals("local")) {         // "local" is now "file:///".
      LOG.warn("\"local\" is a deprecated filesystem name."
               +" Use \"file:///\" instead.");
      name = "file:///";
    } else if (name.indexOf('/')==-1) {   // unqualified is "hdfs://"
      LOG.warn("\""+name+"\" is a deprecated filesystem name."
               +" Use \"hdfs://"+name+"/\" instead.");
      name = "hdfs://"+name;
    }
    return name;
  }

  /**
   * Get the local file syste
   * @param conf the configuration to configure the file system with
   * @return a LocalFileSystem
   */
  public static LocalFileSystem getLocal(Configuration conf)
    throws IOException {
    return (LocalFileSystem)get(LocalFileSystem.NAME, conf);
  }

  /** Returns the FileSystem for this URI's scheme and authority.  The scheme
   * of the URI determines a configuration property name,
   * <tt>fs.<i>scheme</i>.class</tt> whose value names the FileSystem class.
   * The entire URI is passed to the FileSystem instance's initialize method.
   */
  public static FileSystem get(URI uri, Configuration conf) throws IOException {
    String scheme = uri.getScheme();
    String authority = uri.getAuthority();

    if (scheme == null && authority == null) {     // use default FS
      return get(conf);
    }

    if (scheme != null && authority == null) {     // no authority
      URI defaultUri = getDefaultUri(conf);
      if (scheme.equals(defaultUri.getScheme())    // if scheme matches default
          && defaultUri.getAuthority() != null) {  // & default has authority
        return get(defaultUri, conf);              // return default
      }
    }
    
    String disableCacheName = String.format("fs.%s.impl.disable.cache", scheme);
    if (conf.getBoolean(disableCacheName, false)) {
      return createFileSystem(uri, conf);
    }

    return CACHE.get(uri, conf);
  }

  private static class ClientFinalizer extends Thread {
    public synchronized void run() {
      try {
        FileSystem.closeAll();
      } catch (IOException e) {
        LOG.info("FileSystem.closeAll() threw an exception:\n" + e);
      }
    }
  }
  private static final ClientFinalizer clientFinalizer = new ClientFinalizer();

  /**
   * Close all cached filesystems. Be sure those filesystems are not
   * used anymore.
   * 
   * @throws IOException
   */
  public static void closeAll() throws IOException {
    LOG.debug("Starting clear of FileSystem cache with " + CACHE.size() +
              " elements.");
    CACHE.closeAll();
    LOG.debug("Done clearing cache");
  }

  /**
   * Close all cached filesystems for a given UGI. Be sure those filesystems 
   * are not used anymore.
   * @param ugi
   * @throws IOException
   */
  public static void closeAllForUGI(UserGroupInformation ugi) 
  throws IOException {
    CACHE.closeAll(ugi);
  }

  /** Make sure that a path specifies a FileSystem. */
  public Path makeQualified(Path path) {
    checkPath(path);
    return path.makeQualified(this);
  }
    
  /** create a file with the provided permission
   * The permission of the file is set to be the provided permission as in
   * setPermission, not permission&~umask
   * 
   * It is implemented using two RPCs. It is understood that it is inefficient,
   * but the implementation is thread-safe. The other option is to change the
   * value of umask in configuration to be 0, but it is not thread-safe.
   * 
   * @param fs file system handle
   * @param file the name of the file to be created
   * @param permission the permission of the file
   * @return an output stream
   * @throws IOException
   */
  public static FSDataOutputStream create(FileSystem fs,
      Path file, FsPermission permission) throws IOException {
    // create the file with default permission
    FSDataOutputStream out = fs.create(file);
    // set its permission to the supplied one
    fs.setPermission(file, permission);
    return out;
  }

  /** create a directory with the provided permission
   * The permission of the directory is set to be the provided permission as in
   * setPermission, not permission&~umask
   * 
   * @see #create(FileSystem, Path, FsPermission)
   * 
   * @param fs file system handle
   * @param dir the name of the directory to be created
   * @param permission the permission of the directory
   * @return true if the directory creation succeeds; false otherwise
   * @throws IOException
   */
  public static boolean mkdirs(FileSystem fs, Path dir, FsPermission permission)
  throws IOException {
    // create the directory using the default permission
    boolean result = fs.mkdirs(dir);
    // set its permission to be the supplied one
    fs.setPermission(dir, permission);
    return result;
  }

  ///////////////////////////////////////////////////////////////
  // FileSystem
  ///////////////////////////////////////////////////////////////

  protected FileSystem() {
    super(null);
  }

  /** Check that a Path belongs to this FileSystem. */
  protected void checkPath(Path path) {
    URI uri = path.toUri();
    String thatScheme = uri.getScheme();
    if (thatScheme == null)                // fs is relative 
      return;
    URI thisUri = getCanonicalUri();
    String thisScheme = thisUri.getScheme();
    //authority and scheme are not case sensitive
    if (thisScheme.equalsIgnoreCase(thatScheme)) {// schemes match
      String thisAuthority = thisUri.getAuthority();
      String thatAuthority = uri.getAuthority();
      if (thatAuthority == null &&                // path's authority is null
          thisAuthority != null) {                // fs has an authority
        URI defaultUri = getDefaultUri(getConf());
        if (thisScheme.equalsIgnoreCase(defaultUri.getScheme())) {
          uri = defaultUri; // schemes match, so use this uri instead
        } else {
          uri = null; // can't determine auth of the path
        }
      }
      if (uri != null) {
        // canonicalize uri before comparing with this fs
        uri = NetUtils.getCanonicalUri(uri, getDefaultPort());
        thatAuthority = uri.getAuthority();
        if (thisAuthority == thatAuthority ||       // authorities match
            (thisAuthority != null && 
             thisAuthority.equalsIgnoreCase(thatAuthority)))
          return;
      }
    }
    throw new IllegalArgumentException("Wrong FS: "+path+
                                       ", expected: "+this.getUri());
  }

  /**
   * Return an array containing hostnames, offset and size of 
   * portions of the given file.  For a nonexistent 
   * file or regions, null will be returned.
   *
   * This call is most helpful with DFS, where it returns 
   * hostnames of machines that contain the given file.
   *
   * The FileSystem will simply return an elt containing 'localhost'.
   */
  public BlockLocation[] getFileBlockLocations(FileStatus file, 
      long start, long len) throws IOException {
    if (file == null) {
      return null;
    }

    if ( (start<0) || (len < 0) ) {
      throw new IllegalArgumentException("Invalid start or len parameter");
    }

    if (file.getLen() <= start) {
      return new BlockLocation[0];

    }
    String[] name = { "localhost:50010" };
    String[] host = { "localhost" };
    return new BlockLocation[] { new BlockLocation(name, host, 0, file.getLen()) };
  }
  
  /**
   * Opens an FSDataInputStream at the indicated Path.
   * @param f the file name to open
   * @param bufferSize the size of the buffer to be used.
   */
  public abstract FSDataInputStream open(Path f, int bufferSize)
    throws IOException;
    
  /**
   * Opens an FSDataInputStream at the indicated Path.
   * @param f the file to open
   */
  public FSDataInputStream open(Path f) throws IOException {
    return open(f, getConf().getInt("io.file.buffer.size", 4096));
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path.
   * Files are overwritten by default.
   */
  public FSDataOutputStream create(Path f) throws IOException {
    return create(f, true);
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path.
   */
  public FSDataOutputStream create(Path f, boolean overwrite)
    throws IOException {
    return create(f, overwrite, 
                  getConf().getInt("io.file.buffer.size", 4096),
                  getDefaultReplication(),
                  getDefaultBlockSize());
  }

  /**
   * Create an FSDataOutputStream at the indicated Path with write-progress
   * reporting.
   * Files are overwritten by default.
   */
  public FSDataOutputStream create(Path f, Progressable progress) throws IOException {
    return create(f, true, 
                  getConf().getInt("io.file.buffer.size", 4096),
                  getDefaultReplication(),
                  getDefaultBlockSize(), progress);
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path.
   * Files are overwritten by default.
   */
  public FSDataOutputStream create(Path f, short replication)
    throws IOException {
    return create(f, true, 
                  getConf().getInt("io.file.buffer.size", 4096),
                  replication,
                  getDefaultBlockSize());
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path with write-progress
   * reporting.
   * Files are overwritten by default.
   */
  public FSDataOutputStream create(Path f, short replication, Progressable progress)
    throws IOException {
    return create(f, true, 
                  getConf().getInt("io.file.buffer.size", 4096),
                  replication,
                  getDefaultBlockSize(), progress);
  }

    
  /**
   * Opens an FSDataOutputStream at the indicated Path.
   * @param f the file name to open
   * @param overwrite if a file with this name already exists, then if true,
   *   the file will be overwritten, and if false an error will be thrown.
   * @param bufferSize the size of the buffer to be used.
   */
  public FSDataOutputStream create(Path f, 
                                   boolean overwrite,
                                   int bufferSize
                                   ) throws IOException {
    return create(f, overwrite, bufferSize, 
                  getDefaultReplication(),
                  getDefaultBlockSize());
  }
    
  /**
   * Opens an FSDataOutputStream at the indicated Path with write-progress
   * reporting.
   * @param f the file name to open
   * @param overwrite if a file with this name already exists, then if true,
   *   the file will be overwritten, and if false an error will be thrown.
   * @param bufferSize the size of the buffer to be used.
   */
  public FSDataOutputStream create(Path f, 
                                   boolean overwrite,
                                   int bufferSize,
                                   Progressable progress
                                   ) throws IOException {
    return create(f, overwrite, bufferSize, 
                  getDefaultReplication(),
                  getDefaultBlockSize(), progress);
  }
    
    
  /**
   * Opens an FSDataOutputStream at the indicated Path.
   * @param f the file name to open
   * @param overwrite if a file with this name already exists, then if true,
   *   the file will be overwritten, and if false an error will be thrown.
   * @param bufferSize the size of the buffer to be used.
   * @param replication required block replication for the file. 
   */
  public FSDataOutputStream create(Path f, 
                                   boolean overwrite,
                                   int bufferSize,
                                   short replication,
                                   long blockSize
                                   ) throws IOException {
    return create(f, overwrite, bufferSize, replication, blockSize, null);
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path with write-progress
   * reporting.
   * @param f the file name to open
   * @param overwrite if a file with this name already exists, then if true,
   *   the file will be overwritten, and if false an error will be thrown.
   * @param bufferSize the size of the buffer to be used.
   * @param replication required block replication for the file. 
   */
  public FSDataOutputStream create(Path f,
                                            boolean overwrite,
                                            int bufferSize,
                                            short replication,
                                            long blockSize,
                                            Progressable progress
                                            ) throws IOException {
    return this.create(f, FsPermission.getDefault(),
        overwrite, bufferSize, replication, blockSize, progress);
  }

  /**
   * Opens an FSDataOutputStream at the indicated Path with write-progress
   * reporting.
   * @param f the file name to open
   * @param permission
   * @param overwrite if a file with this name already exists, then if true,
   *   the file will be overwritten, and if false an error will be thrown.
   * @param bufferSize the size of the buffer to be used.
   * @param replication required block replication for the file.
   * @param blockSize
   * @param progress
   * @throws IOException
   * @see #setPermission(Path, FsPermission)
   */
  public abstract FSDataOutputStream create(Path f,
      FsPermission permission,
      boolean overwrite,
      int bufferSize,
      short replication,
      long blockSize,
      Progressable progress) throws IOException;

  /**
  * Opens an FSDataOutputStream at the indicated Path with write-progress
  * reporting. Same as create(), except fails if parent directory doesn't
  * already exist.
  * @param f the file name to open
  * @param overwrite if a file with this name already exists, then if true,
  * the file will be overwritten, and if false an error will be thrown.
  * @param bufferSize the size of the buffer to be used.
  * @param replication required block replication for the file.
  * @param blockSize
  * @param progress
  * @throws IOException
  * @see #setPermission(Path, FsPermission)
  * @deprecated API only for 0.20-append
  */
  @Deprecated
  public FSDataOutputStream createNonRecursive(Path f,
      boolean overwrite,
      int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {
    return this.createNonRecursive(f, FsPermission.getDefault(),
        overwrite, bufferSize, replication, blockSize, progress);
  }
  
  /**
  * Opens an FSDataOutputStream at the indicated Path with write-progress
  * reporting. Same as create(), except fails if parent directory doesn't
  * already exist.
  * @param f the file name to open
  * @param permission
  * @param overwrite if a file with this name already exists, then if true,
  * the file will be overwritten, and if false an error will be thrown.
  * @param bufferSize the size of the buffer to be used.
  * @param replication required block replication for the file.
  * @param blockSize
  * @param progress
  * @throws IOException
  * @see #setPermission(Path, FsPermission)
  * @deprecated API only for 0.20-append
  */
  @Deprecated
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission,
      boolean overwrite,
      int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {
    throw new IOException("createNonRecursive unsupported for this filesystem "
        + this.getClass());
  }

  /**
   * Creates the given Path as a brand-new zero-length file.  If
   * create fails, or if it already existed, return false.
   */
  public boolean createNewFile(Path f) throws IOException {
    if (exists(f)) {
      return false;
    } else {
      create(f, false, getConf().getInt("io.file.buffer.size", 4096)).close();
      return true;
    }
  }

  /**
   * Append to an existing file (optional operation).
   * Same as append(f, getConf().getInt("io.file.buffer.size", 4096), null)
   * @param f the existing file to be appended.
   * @throws IOException
   */
  public FSDataOutputStream append(Path f) throws IOException {
    return append(f, getConf().getInt("io.file.buffer.size", 4096), null);
  }
  /**
   * Append to an existing file (optional operation).
   * Same as append(f, bufferSize, null).
   * @param f the existing file to be appended.
   * @param bufferSize the size of the buffer to be used.
   * @throws IOException
   */
  public FSDataOutputStream append(Path f, int bufferSize) throws IOException {
    return append(f, bufferSize, null);
  }

  /**
   * Append to an existing file (optional operation).
   * @param f the existing file to be appended.
   * @param bufferSize the size of the buffer to be used.
   * @param progress for reporting progress if it is not null.
   * @throws IOException
   */
  public abstract FSDataOutputStream append(Path f, int bufferSize,
      Progressable progress) throws IOException;
  
  /**
   * Get replication.
   * 
   * @deprecated Use getFileStatus() instead
   * @param src file name
   * @return file replication
   * @throws IOException
   */ 
  @Deprecated
  public short getReplication(Path src) throws IOException {
    return getFileStatus(src).getReplication();
  }

  /**
   * Set replication for an existing file.
   * 
   * @param src file name
   * @param replication new replication
   * @throws IOException
   * @return true if successful;
   *         false if file does not exist or is a directory
   */
  public boolean setReplication(Path src, short replication)
    throws IOException {
    return true;
  }

  /**
   * Renames Path src to Path dst.  Can take place on local fs
   * or remote DFS.
   */
  public abstract boolean rename(Path src, Path dst) throws IOException;
    
  /** Delete a file. */
  /** @deprecated Use delete(Path, boolean) instead */ @Deprecated 
  public abstract boolean delete(Path f) throws IOException;
  
  /** Delete a file.
   *
   * @param f the path to delete.
   * @param recursive if path is a directory and set to 
   * true, the directory is deleted else throws an exception. In
   * case of a file the recursive can be set to either true or false. 
   * @return  true if delete is successful else false. 
   * @throws IOException
   */
  public abstract boolean delete(Path f, boolean recursive) throws IOException;

  /**
   * Mark a path to be deleted when FileSystem is closed.
   * When the JVM shuts down,
   * all FileSystem objects will be closed automatically.
   * Then,
   * the marked path will be deleted as a result of closing the FileSystem.
   *
   * The path has to exist in the file system.
   * 
   * @param f the path to delete.
   * @return  true if deleteOnExit is successful, otherwise false.
   * @throws IOException
   */
  public boolean deleteOnExit(Path f) throws IOException {
    if (!exists(f)) {
      return false;
    }
    synchronized (deleteOnExit) {
      deleteOnExit.add(f);
    }
    return true;
  }

  /**
   * Delete all files that were marked as delete-on-exit. This recursively
   * deletes all files in the specified paths.
   */
  protected void processDeleteOnExit() {
    synchronized (deleteOnExit) {
      for (Iterator<Path> iter = deleteOnExit.iterator(); iter.hasNext();) {
        Path path = iter.next();
        try {
          delete(path, true);
        }
        catch (IOException e) {
          LOG.info("Ignoring failure to deleteOnExit for path " + path);
        }
        iter.remove();
      }
    }
  }
  
  /** Check if exists.
   * @param f source file
   */
  public boolean exists(Path f) throws IOException {
    try {
      return getFileStatus(f) != null;
    } catch (FileNotFoundException e) {
      return false;
    }
  }

  /** True iff the named path is a directory. */
  /** @deprecated Use getFileStatus() instead */ @Deprecated
  public boolean isDirectory(Path f) throws IOException {
    try {
      return getFileStatus(f).isDir();
    } catch (FileNotFoundException e) {
      return false;               // f does not exist
    }
  }

  /** True iff the named path is a regular file. */
  public boolean isFile(Path f) throws IOException {
    try {
      return !getFileStatus(f).isDir();
    } catch (FileNotFoundException e) {
      return false;               // f does not exist
    }
  }
    
  /** The number of bytes in a file. */
  /** @deprecated Use getFileStatus() instead */ @Deprecated
  public long getLength(Path f) throws IOException {
    return getFileStatus(f).getLen();
  }
    
  /** Return the {@link ContentSummary} of a given {@link Path}. */
  public ContentSummary getContentSummary(Path f) throws IOException {
    FileStatus status = getFileStatus(f);
    if (!status.isDir()) {
      // f is a file
      return new ContentSummary(status.getLen(), 1, 0);
    }
    // f is a directory
    long[] summary = {0, 0, 1};
    for(FileStatus s : listStatus(f)) {
      ContentSummary c = s.isDir() ? getContentSummary(s.getPath()) :
                                     new ContentSummary(s.getLen(), 1, 0);
      summary[0] += c.getLength();
      summary[1] += c.getFileCount();
      summary[2] += c.getDirectoryCount();
    }
    return new ContentSummary(summary[0], summary[1], summary[2]);
  }

  final private static PathFilter DEFAULT_FILTER = new PathFilter() {
      public boolean accept(Path file) {
        return true;
      }     
    };
    
  /**
   * List the statuses of the files/directories in the given path if the path is
   * a directory.
   * 
   * @param f
   *          given path
   * @return the statuses of the files/directories in the given patch
   *         returns null, if Path f does not exist in the FileSystem
   * @throws IOException
   */
  public abstract FileStatus[] listStatus(Path f) throws IOException;
    
  /*
   * Filter files/directories in the given path using the user-supplied path
   * filter. Results are added to the given array <code>results</code>.
   */
  private void listStatus(ArrayList<FileStatus> results, Path f,
      PathFilter filter) throws IOException {
    FileStatus listing[] = listStatus(f);
    if (listing != null) {
      for (int i = 0; i < listing.length; i++) {
        if (filter.accept(listing[i].getPath())) {
          results.add(listing[i]);
        }
      }
    }
  }

  /**
   * Filter files/directories in the given path using the user-supplied path
   * filter.
   * 
   * @param f
   *          a path name
   * @param filter
   *          the user-supplied path filter
   * @return an array of FileStatus objects for the files under the given path
   *         after applying the filter
   * @throws IOException
   *           if encounter any problem while fetching the status
   */
  public FileStatus[] listStatus(Path f, PathFilter filter) throws IOException {
    ArrayList<FileStatus> results = new ArrayList<FileStatus>();
    listStatus(results, f, filter);
    return results.toArray(new FileStatus[results.size()]);
  }

  /**
   * Filter files/directories in the given list of paths using default
   * path filter.
   * 
   * @param files
   *          a list of paths
   * @return a list of statuses for the files under the given paths after
   *         applying the filter default Path filter
   * @exception IOException
   */
  public FileStatus[] listStatus(Path[] files)
      throws IOException {
    return listStatus(files, DEFAULT_FILTER);
  }

  /**
   * Filter files/directories in the given list of paths using user-supplied
   * path filter.
   * 
   * @param files
   *          a list of paths
   * @param filter
   *          the user-supplied path filter
   * @return a list of statuses for the files under the given paths after
   *         applying the filter
   * @exception IOException
   */
  public FileStatus[] listStatus(Path[] files, PathFilter filter)
      throws IOException {
    ArrayList<FileStatus> results = new ArrayList<FileStatus>();
    for (int i = 0; i < files.length; i++) {
      listStatus(results, files[i], filter);
    }
    return results.toArray(new FileStatus[results.size()]);
  }

  /**
   * <p>Return all the files that match filePattern and are not checksum
   * files. Results are sorted by their names.
   * 
   * <p>
   * A filename pattern is composed of <i>regular</i> characters and
   * <i>special pattern matching</i> characters, which are:
   *
   * <dl>
   *  <dd>
   *   <dl>
   *    <p>
   *    <dt> <tt> ? </tt>
   *    <dd> Matches any single character.
   *
   *    <p>
   *    <dt> <tt> * </tt>
   *    <dd> Matches zero or more characters.
   *
   *    <p>
   *    <dt> <tt> [<i>abc</i>] </tt>
   *    <dd> Matches a single character from character set
   *     <tt>{<i>a,b,c</i>}</tt>.
   *
   *    <p>
   *    <dt> <tt> [<i>a</i>-<i>b</i>] </tt>
   *    <dd> Matches a single character from the character range
   *     <tt>{<i>a...b</i>}</tt>.  Note that character <tt><i>a</i></tt> must be
   *     lexicographically less than or equal to character <tt><i>b</i></tt>.
   *
   *    <p>
   *    <dt> <tt> [^<i>a</i>] </tt>
   *    <dd> Matches a single character that is not from character set or range
   *     <tt>{<i>a</i>}</tt>.  Note that the <tt>^</tt> character must occur
   *     immediately to the right of the opening bracket.
   *
   *    <p>
   *    <dt> <tt> \<i>c</i> </tt>
   *    <dd> Removes (escapes) any special meaning of character <i>c</i>.
   *
   *    <p>
   *    <dt> <tt> {ab,cd} </tt>
   *    <dd> Matches a string from the string set <tt>{<i>ab, cd</i>} </tt>
   *    
   *    <p>
   *    <dt> <tt> {ab,c{de,fh}} </tt>
   *    <dd> Matches a string from the string set <tt>{<i>ab, cde, cfh</i>}</tt>
   *
   *   </dl>
   *  </dd>
   * </dl>
   *
   * @param pathPattern a regular expression specifying a pth pattern

   * @return an array of paths that match the path pattern
   * @throws IOException
   */
  public FileStatus[] globStatus(Path pathPattern) throws IOException {
    return globStatus(pathPattern, DEFAULT_FILTER);
  }
  
  /**
   * Return an array of FileStatus objects whose path names match pathPattern
   * and is accepted by the user-supplied path filter. Results are sorted by
   * their path names.
   * Return null if pathPattern has no glob and the path does not exist.
   * Return an empty array if pathPattern has a glob and no path matches it. 
   * 
   * @param pathPattern
   *          a regular expression specifying the path pattern
   * @param filter
   *          a user-supplied path filter
   * @return an array of FileStatus objects
   * @throws IOException if any I/O error occurs when fetching file status
   */
  public FileStatus[] globStatus(Path pathPattern, PathFilter filter)
      throws IOException {
    String filename = pathPattern.toUri().getPath();
    List<String> filePatterns = GlobExpander.expand(filename);
    if (filePatterns.size() == 1) {
      return globStatusInternal(pathPattern, filter);
    } else {
      List<FileStatus> results = new ArrayList<FileStatus>();
      for (String filePattern : filePatterns) {
        FileStatus[] files = globStatusInternal(new Path(filePattern), filter);
        for (FileStatus file : files) {
          results.add(file);
        }
      }
      return results.toArray(new FileStatus[results.size()]);
    }
  }

  private FileStatus[] globStatusInternal(Path pathPattern, PathFilter filter)
      throws IOException {
    Path[] parents = new Path[1];
    int level = 0;
    String filename = pathPattern.toUri().getPath();
    
    // path has only zero component
    if ("".equals(filename) || Path.SEPARATOR.equals(filename)) {
      return getFileStatus(new Path[]{pathPattern});
    }

    // path has at least one component
    String[] components = filename.split(Path.SEPARATOR);
    // get the first component
    if (pathPattern.isAbsolute()) {
      parents[0] = new Path(Path.SEPARATOR);
      level = 1;
    } else {
      parents[0] = new Path(Path.CUR_DIR);
    }

    // glob the paths that match the parent path, i.e., [0, components.length-1]
    boolean[] hasGlob = new boolean[]{false};
    Path[] parentPaths = globPathsLevel(parents, components, level, hasGlob);
    FileStatus[] results;
    if (parentPaths == null || parentPaths.length == 0) {
      results = null;
    } else {
      // Now work on the last component of the path
      GlobFilter fp = new GlobFilter(components[components.length - 1], filter);
      if (fp.hasPattern()) { // last component has a pattern
        // list parent directories and then glob the results
        results = listStatus(parentPaths, fp);
        hasGlob[0] = true;
      } else { // last component does not have a pattern
        // get all the path names
        ArrayList<Path> filteredPaths = new ArrayList<Path>(parentPaths.length);
        for (int i = 0; i < parentPaths.length; i++) {
          parentPaths[i] = new Path(parentPaths[i],
            components[components.length - 1]);
          if (fp.accept(parentPaths[i])) {
            filteredPaths.add(parentPaths[i]);
          }
        }
        // get all their statuses
        results = getFileStatus(
            filteredPaths.toArray(new Path[filteredPaths.size()]));
      }
    }

    // Decide if the pathPattern contains a glob or not
    if (results == null) {
      if (hasGlob[0]) {
        results = new FileStatus[0];
      }
    } else {
      if (results.length == 0 ) {
        if (!hasGlob[0]) {
          results = null;
        }
      } else {
        Arrays.sort(results);
      }
    }
    return results;
  }

  /*
   * For a path of N components, return a list of paths that match the
   * components [<code>level</code>, <code>N-1</code>].
   */
  private Path[] globPathsLevel(Path[] parents, String[] filePattern,
      int level, boolean[] hasGlob) throws IOException {
    if (level == filePattern.length - 1)
      return parents;
    if (parents == null || parents.length == 0) {
      return null;
    }
    GlobFilter fp = new GlobFilter(filePattern[level]);
    if (fp.hasPattern()) {
      parents = FileUtil.stat2Paths(listStatus(parents, fp));
      hasGlob[0] = true;
    } else {
      for (int i = 0; i < parents.length; i++) {
        parents[i] = new Path(parents[i], filePattern[level]);
      }
    }
    return globPathsLevel(parents, filePattern, level + 1, hasGlob);
  }
    
  /** Return the current user's home directory in this filesystem.
   * The default implementation returns "/user/$USER/".
   */
  public Path getHomeDirectory() {
    return new Path("/user/"+System.getProperty("user.name"))
      .makeQualified(this);
  }

  /**
   * Get a new delegation token for this file system.
   * @param renewer the account name that is allowed to renew the token.
   * @return a new delegation token
   * @throws IOException
   */
  public Token<?> getDelegationToken(String renewer) throws IOException {
    return null;
  }

  /**
   * Set the current working directory for the given file system. All relative
   * paths will be resolved relative to it.
   * 
   * @param new_dir
   */
  public abstract void setWorkingDirectory(Path new_dir);
    
  /**
   * Get the current working directory for the given file system
   * @return the directory pathname
   */
  public abstract Path getWorkingDirectory();

  /**
   * Call {@link #mkdirs(Path, FsPermission)} with default permission.
   */
  public boolean mkdirs(Path f) throws IOException {
    return mkdirs(f, FsPermission.getDefault());
  }

  /**
   * Make the given file and all non-existent parents into
   * directories. Has the semantics of Unix 'mkdir -p'.
   * Existence of the directory hierarchy is not an error.
   */
  public abstract boolean mkdirs(Path f, FsPermission permission
      ) throws IOException;

  /**
   * The src file is on the local disk.  Add it to FS at
   * the given dst name and the source is kept intact afterwards
   */
  public void copyFromLocalFile(Path src, Path dst)
    throws IOException {
    copyFromLocalFile(false, src, dst);
  }

  /**
   * The src files is on the local disk.  Add it to FS at
   * the given dst name, removing the source afterwards.
   */
  public void moveFromLocalFile(Path[] srcs, Path dst)
    throws IOException {
    copyFromLocalFile(true, true, srcs, dst);
  }

  /**
   * The src file is on the local disk.  Add it to FS at
   * the given dst name, removing the source afterwards.
   */
  public void moveFromLocalFile(Path src, Path dst)
    throws IOException {
    copyFromLocalFile(true, src, dst);
  }

  /**
   * The src file is on the local disk.  Add it to FS at
   * the given dst name.
   * delSrc indicates if the source should be removed
   */
  public void copyFromLocalFile(boolean delSrc, Path src, Path dst)
    throws IOException {
    copyFromLocalFile(delSrc, true, src, dst);
  }
  
  /**
   * The src files are on the local disk.  Add it to FS at
   * the given dst name.
   * delSrc indicates if the source should be removed
   */
  public void copyFromLocalFile(boolean delSrc, boolean overwrite, 
                                Path[] srcs, Path dst)
    throws IOException {
    Configuration conf = getConf();
    FileUtil.copy(getLocal(conf), srcs, this, dst, delSrc, overwrite, conf);
  }
  
  /**
   * The src file is on the local disk.  Add it to FS at
   * the given dst name.
   * delSrc indicates if the source should be removed
   */
  public void copyFromLocalFile(boolean delSrc, boolean overwrite, 
                                Path src, Path dst)
    throws IOException {
    Configuration conf = getConf();
    FileUtil.copy(getLocal(conf), src, this, dst, delSrc, overwrite, conf);
  }
    
  /**
   * The src file is under FS, and the dst is on the local disk.
   * Copy it from FS control to the local dst name.
   */
  public void copyToLocalFile(Path src, Path dst) throws IOException {
    copyToLocalFile(false, src, dst);
  }
    
  /**
   * The src file is under FS, and the dst is on the local disk.
   * Copy it from FS control to the local dst name.
   * Remove the source afterwards
   */
  public void moveToLocalFile(Path src, Path dst) throws IOException {
    copyToLocalFile(true, src, dst);
  }

  /**
   * The src file is under FS, and the dst is on the local disk.
   * Copy it from FS control to the local dst name.
   * delSrc indicates if the src will be removed or not.
   */   
  public void copyToLocalFile(boolean delSrc, Path src, Path dst)
    throws IOException {
    FileUtil.copy(this, src, getLocal(getConf()), dst, delSrc, getConf());
  }

  /**
   * Returns a local File that the user can write output to.  The caller
   * provides both the eventual FS target name and the local working
   * file.  If the FS is local, we write directly into the target.  If
   * the FS is remote, we write into the tmp local area.
   */
  public Path startLocalOutput(Path fsOutputFile, Path tmpLocalFile)
    throws IOException {
    return tmpLocalFile;
  }

  /**
   * Called when we're all done writing to the target.  A local FS will
   * do nothing, because we've written to exactly the right place.  A remote
   * FS will copy the contents of tmpLocalFile to the correct target at
   * fsOutputFile.
   */
  public void completeLocalOutput(Path fsOutputFile, Path tmpLocalFile)
    throws IOException {
    moveFromLocalFile(tmpLocalFile, fsOutputFile);
  }

  /**
   * No more filesystem operations are needed.  Will
   * release any held locks.
   */
  public void close() throws IOException {
    // delete all files that were marked as delete-on-exit.
    processDeleteOnExit();
    CACHE.remove(this.key, this);
    LOG.debug("Removing filesystem for " + getUri());
  }

  /** Return the total size of all files in the filesystem.*/
  public long getUsed() throws IOException{
    long used = 0;
    FileStatus[] files = listStatus(new Path("/"));
    for(FileStatus file:files){
      used += file.getLen();
    }
    return used;
  }

  /**
   * Get the block size for a particular file.
   * @param f the filename
   * @return the number of bytes in a block
   */
  /** @deprecated Use getFileStatus() instead */ @Deprecated
  public long getBlockSize(Path f) throws IOException {
    return getFileStatus(f).getBlockSize();
  }
    
  /**
   * Return the number of bytes that large input files should be optimally
   * be split into to minimize i/o time.
   * @deprecated use {@link #getDefaultBlockSize(Path)} instead
   */
  @Deprecated
  public long getDefaultBlockSize() {
    // default to 32MB: large enough to minimize the impact of seeks
    return getConf().getLong("fs.local.block.size", 32 * 1024 * 1024);
  }

  /**
   * Return the number of bytes that large input files should be optimally
   * be split into to minimize i/o time.
   * @param f path of file
   * @return the default block size for the path's filesystem
   */
  public long getDefaultBlockSize(Path f) {
    return getDefaultBlockSize();
  }
    
  /**
   * Get the default replication.
   * @deprecated use {@link #getDefaultReplication(Path)} instead
   */
  @Deprecated
  public short getDefaultReplication() { return 1; }

  /**
   * Get the default replication.
   * @param path of the file
   * @return default replication for the path's filesystem 
   */
  public short getDefaultReplication(Path path) {
    return getDefaultReplication();
  }

  /**
   * Return a file status object that represents the path.
   * @param f The path we want information from
   * @return a FileStatus object
   * @throws FileNotFoundException when the path does not exist;
   *         IOException see specific implementation
   */
  public abstract FileStatus getFileStatus(Path f) throws IOException;

  /**
   * Get the checksum of a file.
   *
   * @param f The file path
   * @return The file checksum.  The default return value is null,
   *  which indicates that no checksum algorithm is implemented
   *  in the corresponding FileSystem.
   */
  public FileChecksum getFileChecksum(Path f) throws IOException {
    return null;
  }
  
  /**
   * Set the verify checksum flag. This is only applicable if the 
   * corresponding FileSystem supports checksum. By default doesn't do anything.
   * @param verifyChecksum
   */
  public void setVerifyChecksum(boolean verifyChecksum) {
    //doesn't do anything
  }

  /**
   * Return a list of file status objects that corresponds to the list of paths
   * excluding those non-existent paths.
   * 
   * @param paths
   *          the list of paths we want information from
   * @return a list of FileStatus objects
   * @throws IOException
   *           see specific implementation
   */
  private FileStatus[] getFileStatus(Path[] paths) throws IOException {
    if (paths == null) {
      return null;
    }
    ArrayList<FileStatus> results = new ArrayList<FileStatus>(paths.length);
    for (int i = 0; i < paths.length; i++) {
      try {
        results.add(getFileStatus(paths[i]));
      } catch (FileNotFoundException e) { // do nothing
      }
    }
    return results.toArray(new FileStatus[results.size()]);
  }

  /**
   * Set permission of a path.
   * @param p
   * @param permission
   */
  public void setPermission(Path p, FsPermission permission
      ) throws IOException {
  }

  /**
   * Set owner of a path (i.e. a file or a directory).
   * The parameters username and groupname cannot both be null.
   * @param p The path
   * @param username If it is null, the original username remains unchanged.
   * @param groupname If it is null, the original groupname remains unchanged.
   */
  public void setOwner(Path p, String username, String groupname
      ) throws IOException {
  }

  /**
   * Set access time of a file
   * @param p The path
   * @param mtime Set the modification time of this file.
   *              The number of milliseconds since Jan 1, 1970. 
   *              A value of -1 means that this call should not set modification time.
   * @param atime Set the access time of this file.
   *              The number of milliseconds since Jan 1, 1970. 
   *              A value of -1 means that this call should not set access time.
   */
  public void setTimes(Path p, long mtime, long atime
      ) throws IOException {
  }

  private static FileSystem createFileSystem(URI uri, Configuration conf
      ) throws IOException {
    Class<?> clazz = conf.getClass("fs." + uri.getScheme() + ".impl", null);
    LOG.debug("Creating filesystem for " + uri);
    if (clazz == null) {
      throw new IOException("No FileSystem for scheme: " + uri.getScheme());
    }
    FileSystem fs = (FileSystem)ReflectionUtils.newInstance(clazz, conf);
    fs.initialize(uri, conf);
    return fs;
  }

  /** Caching FileSystem objects */
  static class Cache {
    private final Map<Key, FileSystem> map = new HashMap<Key, FileSystem>();

    FileSystem get(URI uri, Configuration conf) throws IOException{
      Key key = new Key(uri, conf);
      FileSystem fs = null;
      synchronized (this) {
        fs = map.get(key);
      }
      if (fs != null) {
        return fs;
      }
      
      fs = createFileSystem(uri, conf);
      synchronized (this) {  // refetch the lock again
        FileSystem oldfs = map.get(key);
        if (oldfs != null) { // a file system is created while lock is releasing
          fs.close(); // close the new file system
          return oldfs;  // return the old file system
        }

        // now insert the new file system into the map
        if (map.isEmpty() && !clientFinalizer.isAlive()) {
          Runtime.getRuntime().addShutdownHook(clientFinalizer);
        }
        fs.key = key;
        map.put(key, fs);
        return fs;
      }
    }

    synchronized void remove(Key key, FileSystem fs) {
      if (map.containsKey(key) && fs == map.get(key)) {
        map.remove(key);
        if (map.isEmpty() && !clientFinalizer.isAlive()) {
          if (!Runtime.getRuntime().removeShutdownHook(clientFinalizer)) {
            LOG.info("Could not cancel cleanup thread, though no " +
                     "FileSystems are open");
          }
        }
      }
    }

    synchronized void closeAll() throws IOException {
      List<IOException> exceptions = new ArrayList<IOException>();
      for(; !map.isEmpty(); ) {
        Map.Entry<Key, FileSystem> e = map.entrySet().iterator().next();
        final Key key = e.getKey();
        final FileSystem fs = e.getValue();

        //remove from cache
        remove(key, fs);

        if (fs != null) {
          try {
            fs.close();
          }
          catch(IOException ioe) {
            exceptions.add(ioe);
          }
        }
      }

      if (!exceptions.isEmpty()) {
        throw MultipleIOException.createIOException(exceptions);
      }
    }

    synchronized void closeAll(UserGroupInformation ugi) throws IOException {
      List<FileSystem> targetFSList = new ArrayList<FileSystem>();
      //Make a pass over the list and collect the filesystems to close
      //we cannot close inline since close() removes the entry from the Map
      for (Map.Entry<Key, FileSystem> entry : map.entrySet()) {
        final Key key = entry.getKey();
        final FileSystem fs = entry.getValue();
        if (ugi.equals(key.ugi) && fs != null) {
          targetFSList.add(fs);   
        }
      }
      List<IOException> exceptions = new ArrayList<IOException>();
      //now make a pass over the target list and close each
      for (FileSystem fs : targetFSList) {
        try {
          fs.close();
        }
        catch(IOException ioe) {
          exceptions.add(ioe);
        }
      }
      if (!exceptions.isEmpty()) {
        throw MultipleIOException.createIOException(exceptions);
      }
    }

    /** FileSystem.Cache.Key */
    static class Key {
      final String scheme;
      final String authority;
      final UserGroupInformation ugi;

      Key(URI uri, Configuration conf) throws IOException {
        scheme = uri.getScheme()==null?"":uri.getScheme().toLowerCase();
        authority = uri.getAuthority()==null?"":uri.getAuthority().toLowerCase();
        this.ugi = UserGroupInformation.getCurrentUser();
      }

      /** {@inheritDoc} */
      public int hashCode() {
        return (scheme + authority).hashCode() + ugi.hashCode();
      }

      static boolean isEqual(Object a, Object b) {
        return a == b || (a != null && a.equals(b));        
      }

      /** {@inheritDoc} */
      public boolean equals(Object obj) {
        if (obj == this) {
          return true;
        }
        if (obj != null && obj instanceof Key) {
          Key that = (Key)obj;
          return isEqual(this.scheme, that.scheme)
                 && isEqual(this.authority, that.authority)
                 && isEqual(this.ugi, that.ugi);
        }
        return false;        
      }

      /** {@inheritDoc} */
      public String toString() {
        return "("+ugi.toString() + ")@" + scheme + "://" + authority;        
      }
    }
    
    /**
     * Get the number of file systems in the cache.
     * @return the number of cached file systems
     */
    int size() {
      return map.size();
    }
  }
  
  public static final class Statistics {
    private final String scheme;
    private AtomicLong bytesRead = new AtomicLong();
    private AtomicLong bytesWritten = new AtomicLong();
    private AtomicInteger readOps = new AtomicInteger();
    private AtomicInteger largeReadOps = new AtomicInteger();
    private AtomicInteger writeOps = new AtomicInteger();
    
    public Statistics(String scheme) {
      this.scheme = scheme;
    }

    /**
     * Increment the bytes read in the statistics
     * @param newBytes the additional bytes read
     */
    public void incrementBytesRead(long newBytes) {
      bytesRead.getAndAdd(newBytes);
    }
    
    /**
     * Increment the bytes written in the statistics
     * @param newBytes the additional bytes written
     */
    public void incrementBytesWritten(long newBytes) {
      bytesWritten.getAndAdd(newBytes);
    }
    
    /**
     * Increment the number of read operations
     * @param count number of read operations
     */
    public void incrementReadOps(int count) {
      readOps.getAndAdd(count);
    }

    /**
     * Increment the number of large read operations
     * @param count number of large read operations
     */
    public void incrementLargeReadOps(int count) {
      largeReadOps.getAndAdd(count);
    }

    /**
     * Increment the number of write operations
     * @param count number of write operations
     */
    public void incrementWriteOps(int count) {
      writeOps.getAndAdd(count);
    }

    /**
     * Get the total number of bytes read
     * @return the number of bytes
     */
    public long getBytesRead() {
      return bytesRead.get();
    }
    
    /**
     * Get the total number of bytes written
     * @return the number of bytes
     */
    public long getBytesWritten() {
      return bytesWritten.get();
    }
    
    /**
     * Get the number of file system read operations such as list files
     * @return number of read operations
     */
    public int getReadOps() {
      return readOps.get() + largeReadOps.get();
    }

    /**
     * Get the number of large file system read operations such as list files
     * under a large directory
     * @return number of large read operations
     */
    public int getLargeReadOps() {
      return largeReadOps.get();
    }

    /**
     * Get the number of file system write operations such as create, append 
     * rename etc.
     * @return number of write operations
     */
    public int getWriteOps() {
      return writeOps.get();
    }

    public String toString() {
      return bytesRead + " bytes read, " + bytesWritten + " bytes written, "
          + readOps + " read ops, " + largeReadOps + " large read ops, "
          + writeOps + " write ops";
    }
    
    /**
     * Reset the counts of bytes to 0.
     */
    public void reset() {
      bytesWritten.set(0);
      bytesRead.set(0);
    }
    
    /**
     * Get the uri scheme associated with this statistics object.
     * @return the schema associated with this set of statistics
     */
    public String getScheme() {
      return scheme;
    }
  }
  
  /**
   * Get the Map of Statistics object indexed by URI Scheme.
   * @return a Map having a key as URI scheme and value as Statistics object
   * @deprecated use {@link #getAllStatistics} instead
   */
  public static synchronized Map<String, Statistics> getStatistics() {
    Map<String, Statistics> result = new HashMap<String, Statistics>();
    for(Statistics stat: statisticsTable.values()) {
      result.put(stat.getScheme(), stat);
    }
    return result;
  }

  /**
   * Return the FileSystem classes that have Statistics
   */
  public static synchronized List<Statistics> getAllStatistics() {
    return new ArrayList<Statistics>(statisticsTable.values());
  }
  
  /**
   * Get the statistics for a particular file system
   * @param cls the class to lookup
   * @return a statistics object
   */
  public static synchronized 
  Statistics getStatistics(String scheme, Class<? extends FileSystem> cls) {
    Statistics result = statisticsTable.get(cls);
    if (result == null) {
      result = new Statistics(scheme);
      statisticsTable.put(cls, result);
    }
    return result;
  }
  
  public static synchronized void clearStatistics() {
    for(Statistics stat: statisticsTable.values()) {
      stat.reset();
    }
  }

  public static synchronized
  void printStatistics() throws IOException {
    for (Map.Entry<Class<? extends FileSystem>, Statistics> pair: 
            statisticsTable.entrySet()) {
      System.out.println("  FileSystem " + pair.getKey().getName() + 
                         ": " + pair.getValue());
    }
  }
}
