package com.alibaba.middleware.race.cache;

import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by yfy on 7/11/16.
 * Cache
 */
public class Cache implements IDiskManager {

  private final int BLOCK_SIZE = 4096;

  // 2 ^ BIT = BLOCK_SIZE
  private final int BIT = 12;

  private final int MASK = 0xfff;

  private final int CACHE_SIZE = 10;

  // blockId, block
  private Map<BlockId, Node> blockMap;

  private Node head, tail;

  // filename, fd
  private Map<String, RandomAccessFile> fileMap;

  public Cache() {
    blockMap = new HashMap<>();
    fileMap = new HashMap<>();
    head = tail = null;
  }

  @Override
  public void read(String filename, long offset, int length, byte[] buf) throws Exception {
    // end offset in file
    long endOffset = offset + length - 1;

    long beginBlockNo = offset >>> BIT;
    long endBlockNo = endOffset >>> BIT;

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
      for (long blockNo = beginBlockNo + 1; blockNo < endBlockNo; blockNo++) {
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

  @Override
  public void write(String filename, long offset, int length, byte[] buf) throws Exception {
    // end offset in file
    long endOffset = offset + length - 1;

    long beginBlockNo = offset >>> BIT;
    long endBlockNo = endOffset >>> BIT;

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
      for (long blockNo = beginBlockNo + 1; blockNo < endBlockNo; blockNo++) {
        writeBlock(new BlockId(filename, blockNo), 0, buf, srcPos, BLOCK_SIZE);
        srcPos += BLOCK_SIZE;
      }

      // last block
      int endOff = (int) (endOffset & MASK);
      writeBlock(new BlockId(filename, endBlockNo), 0, buf, srcPos, endOff + 1);
    }
  }

  // do not modify the return block
  private byte[] readBlock(BlockId blockId) throws Exception {
    Node node = blockMap.get(blockId);
    if (node == null) {  // not in cache
      // read from disk
      byte[] block = new byte[BLOCK_SIZE];
      RandomAccessFile f = getFd(blockId.filename);
      f.seek(blockId.no << BIT);
      f.read(block, 0, BLOCK_SIZE);

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
      remove(node);
      addHead(node);
    }
    return node.block;
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
        f.seek(blockId.no << BIT);
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
    f.seek(node.blockId.no << BIT);
    f.write(node.block, 0, BLOCK_SIZE);
  }

  private RandomAccessFile getFd(String filename) throws Exception {
    RandomAccessFile f = fileMap.get(filename);
    if (f == null) {
      f = new RandomAccessFile(filename, "rwd");
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

  /**
   * newest <--------> oldest
   * head  next  next  tail
   *    .   ->  . ->   .
   *        <-    <-
   *       prev  prev
   */
  private class Node {

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

      f.seek(99999999);
      System.out.println(f.read(buf));
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}