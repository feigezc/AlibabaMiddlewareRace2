package com.alibaba.middleware.race.cache;

import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by yfy on 7/11/16.
 * Cache
 */
public class Cache {

  private final int BLOCK_SIZE = 4096;

  // 2 ^ BIT = BLOCK_SIZE
  private final int BIT = 12;

  private final int MASK = 0xfff;

  private final int CACHE_SIZE = 100000;

  // blockId, block
  private Map<BlockId, Node> blockMap;

  private Node head, tail;

  // filename, fd
  private Map<String, RandomAccessFile> fileMap;

  private static volatile Cache cache;

  private Cache() {
    blockMap = new HashMap<>();
    fileMap = new HashMap<>();
    head = tail = null;
  }

  public static Cache getInstance() {
    if (cache == null) {
      synchronized (Cache.class) {
        if (cache == null)
          cache = new Cache();
      }
    }
    return cache;
  }

  public void read(String filename, long offset, int length, byte[] buf) throws Exception {
    // end offset in file
    long endOffset = offset + length - 1;

    int beginBlockNo = (int) (offset >>> BIT);
    int endBlockNo = (int) (endOffset >>> BIT);

    if (beginBlockNo == endBlockNo) {  // in one block
      // offset in block
      int beginOff = (int) (offset & MASK);
      byte[] block = readBlock(new BlockId(filename, beginBlockNo));
      System.arraycopy(block, beginOff, buf, 0, length);

    } else {  // multiple blocks
      // first block
      int beginOff = (int) (offset & MASK);  // offset in the block
      byte[] block = readBlock(new BlockId(filename, beginBlockNo));
      int readLen = BLOCK_SIZE - beginOff;
      System.arraycopy(block, beginOff, buf, 0, readLen);
      int destPos = readLen;  // next position in buf

      // middle blocks
      for (int blockNo = beginBlockNo + 1; blockNo < endBlockNo; blockNo++) {
        block = readBlock(new BlockId(filename, blockNo));
        System.arraycopy(block, 0, buf, destPos, BLOCK_SIZE);
        destPos += BLOCK_SIZE;
      }

      // last block
      int endOff = (int) (endOffset & MASK);
      block = readBlock(new BlockId(filename, endBlockNo));
      System.arraycopy(block, 0, buf, destPos, endOff + 1);
    }
  }

  public void write(String filename, long offset, int length, byte[] buf) throws Exception {
    // end offset in file
    long endOffset = offset + length - 1;

    int beginBlockNo = (int) (offset >>> BIT);
    int endBlockNo = (int) (endOffset >>> BIT);

    if (beginBlockNo == endBlockNo) {  // in one block
      // offset in block
      int beginOff = (int) (offset & MASK);
      writeBlock(new BlockId(filename, beginBlockNo), beginOff, buf, 0, length);

    } else {  // multiple blocks
      // first block
      int beginOff = (int) (offset & MASK);  // offset in the block
      int writeLen = BLOCK_SIZE - beginOff;
      writeBlock(new BlockId(filename, beginBlockNo), beginOff, buf, 0, writeLen);
      int srcPos = writeLen;  // next position in buf

      // middle blocks
      for (int blockNo = beginBlockNo + 1; blockNo < endBlockNo; blockNo++) {
        writeBlock(new BlockId(filename, blockNo), 0, buf, srcPos, BLOCK_SIZE);
        srcPos += BLOCK_SIZE;
      }

      // last block
      int endOff = (int) (endOffset & MASK);
      writeBlock(new BlockId(filename, endBlockNo), 0, buf, srcPos, endOff + 1);
    }
  }


  // copy entire block to buf
  public synchronized void readBlock(String filename, int blockNo, byte[] buf) throws Exception {
    byte[] block = readBlock(new BlockId(filename, blockNo));
    System.arraycopy(block, 0, buf, 0, BLOCK_SIZE);
  }

  // read from offset in filename to the end of block
  public void readPartBlock(String filename, long offset, byte[] buf) throws Exception {
    int blockNo = (int) (offset >>> BIT);
    int blockOff = (int) (offset & MASK);
    byte[] block = readBlock(new BlockId(filename, blockNo));
    System.arraycopy(block, blockOff, buf, 0, BLOCK_SIZE - blockOff);
  }

  // do not modify the return block
  private byte[] readBlock(BlockId blockId) throws Exception {
    Node node = blockMap.get(blockId);
    if (node == null) {  // not in cache
      // read from disk
      byte[] block = new byte[BLOCK_SIZE];
      RandomAccessFile f = getFd(blockId.filename);
      f.seek(((long) blockId.no) << BIT);
      f.read(block, 0, BLOCK_SIZE);

      node = new Node(blockId, block);

      // remove the lru cache
      if (blockMap.size() >= CACHE_SIZE) {
        writeBlockToDisk(tail);
        remove(tail);
        blockMap.remove(tail.blockId);
      }
      // add a new node into cache
      addHead(node);
      blockMap.put(blockId, node);

    } else {  // in cache
      remove(node);
      addHead(node);
    }
    return node.block;
  }

  // write the entire block
  public synchronized void writeBlock(String filename, int blockNo, byte[] buf) throws Exception {
    writeBlock(new BlockId(filename, blockNo), 0, buf, 0, BLOCK_SIZE);
  }

  // write len bytes of buf at bufOff into block at blockOff
  private void writeBlock(BlockId blockId, int blockOff, byte[] buf, int bufOff, int len) throws Exception {
    Node node = blockMap.get(blockId);
    if (node == null) {  // not in cache
      // new a block, write data
      byte[] block = new byte[BLOCK_SIZE];

      if (blockOff == 0 && len == BLOCK_SIZE) {  // write to cache directly
        System.arraycopy(buf, bufOff, block, 0, BLOCK_SIZE);
      } else {  // read from disk first
        RandomAccessFile f = getFd(blockId.filename);
        f.seek(((long) blockId.no) << BIT);
        f.read(block, 0, BLOCK_SIZE);

        System.arraycopy(buf, bufOff, block, blockOff, len);
      }

      node = new Node(blockId, block);

      // add a new node into cache
      if (blockMap.size() >= CACHE_SIZE) {  // remove the lru cache
        writeBlockToDisk(tail);
        remove(tail);
        blockMap.remove(tail.blockId);
      }
      addHead(node);
      blockMap.put(blockId, node);

    } else {  // in cache
      System.arraycopy(buf, bufOff, node.block, blockOff, len);
      remove(node);
      addHead(node);
    }
  }

  // write to disk immediately
  private void writeBlockToDisk(Node node) throws Exception {
    RandomAccessFile f = getFd(node.blockId.filename);
    f.seek(((long) node.blockId.no) << BIT);
    f.write(node.block, 0, BLOCK_SIZE);
  }

  public void addFd(String filename, boolean readOnly) throws Exception {
    String mode = readOnly ? "r" : "rw";
    fileMap.put(filename, new RandomAccessFile(filename, mode));
  }

  private synchronized RandomAccessFile getFd(String filename) throws Exception {
    RandomAccessFile f = fileMap.get(filename);
    if (f == null) {
      f = new RandomAccessFile(filename, "rw");
      fileMap.put(filename, f);
    }
    return f;
  }

  // remove node in linked list
  private void remove(Node node) {
    if (node.prev == null)
      head = node.next;
    else
      node.prev.next = node.next;

    if (node.next == null)
      tail = node.prev;
    else
      node.next.prev = node.prev;
  }

  // add node in the head
  private void addHead(Node node) {
    if (head == null)
      head = tail = node;
    else {
      node.prev = null;
      node.next = head;
      head.prev = node;
      head = node;
    }
  }

  private void flush() throws Exception {
    Node p = this.head;
    while (p != null) {
      writeBlockToDisk(p);
      p = p.next;
    }
  }

//  protected void finalize() {
//    try {
//      flush();
//    } catch (Exception e) {
//      e.printStackTrace();
//    }
//  }

  /**
   * newest <--------> oldest
   * head  next  next  tail
   *    .   ->  . ->   .
   *        <-    <-
   *       prev  prev
   */
  private static class Node {

    BlockId blockId;

    byte[] block;

    Node prev, next;

    Node(BlockId blockId, byte[] block) {
      this.blockId = blockId;
      this.block = block;
    }
  }

  public static void main(String[] args) {
    try {
      RandomAccessFile f = new RandomAccessFile("test", "rwd");
      byte[] buf = new byte[4096];

      for (int i = 0; i < 20; i++) {
        Arrays.fill(buf, (byte)i);
        f.write(buf);
      }

      Cache cache = new Cache();
      for (int i = 0; i < 20; i++) {
        buf = cache.readBlock(new BlockId("test", i));
        System.out.println(buf[0]);
      }

      f.seek(999999);
      f.write(5);
      System.out.println();

      buf = cache.readBlock(new BlockId("test", 99));
      System.out.println(Arrays.toString(buf));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
