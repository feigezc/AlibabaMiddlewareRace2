package com.alibaba.middleware.race;

import com.alibaba.middleware.race.cache.Cache;

import java.util.Arrays;
import java.util.List;

/**
 * Created by yfy on 7/13/16.
 * HashTable.
 * <p>
 * Bucket structure:
 * 1. 4 byte, blockNo of next bucket of the same hash code
 * if 0, no next bucket
 * 2. 4 byte, current size(total num of bytes) of block
 * if 0, no entrys
 * 3. many entrys
 * <p>
 * 4B   4B
 * next size entry1 entry2 ... entryn
 * <p>
 * Entry structure:
 * 1. fix-size key
 * KEY_SIZE, 4B, 8B
 * key, fileId, offset
 * 2. variable-size key
 * 2B,    keySize,  4B,    8B
 * keySize, key, fileId, offset
 */
public class HashTable {

  // number of buckets
  private final int SIZE;

  // also bucket size
  private final int BLOCK_SIZE = 4096;

  private final boolean keySizeFixed;

  private final int KEY_SIZE;

  private final int ENTRY_SIZE;

  //private final boolean multipleValue;

  // current number of blocks
  private int blockNums;

  private List<String> dataFiles;

  private String indexFile;

  private Cache cache;

  /**
   *
   * @param dataFiles
   * @param indexFile
   * @param size
   * @param keySize -1 when not fixed
   */
  public HashTable(List<String> dataFiles, String indexFile, int size, int keySize) {
    this.dataFiles = dataFiles;
    this.indexFile = indexFile;

    SIZE = size;

    if (keySize == -1) {
      keySizeFixed = false;
      KEY_SIZE = ENTRY_SIZE = 0;
    } else {
      keySizeFixed = true;
      KEY_SIZE = keySize;
      ENTRY_SIZE = KEY_SIZE + 12;
    }

    blockNums = SIZE;
    cache = Cache.getInstance();
  }

  // pay attention to key.length
  public void add(byte[] key, int fileId, long fileOffset) throws Exception {
//    if (key.length != KEY_SIZE)
//      throw new Exception();

    // find the last block with the same hashcode in the chain
    byte[] bucket = new byte[BLOCK_SIZE];
    int blockNo = keyHashCode(key);  // current blockNo
    while (true) {
      cache.readBlock(indexFile, blockNo, bucket);
      if (Util.byte2int(bucket, 0) == 0)  // no next bucket
        break;
      else
        blockNo = Util.byte2int(bucket, 0);
    }

    // the next position in block to add entry
    int nextPos = Util.byte2int(bucket, 4);
    if (nextPos == 0) {
      nextPos = 8;
      System.arraycopy(Util.int2byte(8), 0, bucket, 4, 4);
    }

    // this bucket has no enough space to add entry
    if (keySizeFixed && nextPos + ENTRY_SIZE > BLOCK_SIZE ||
        !keySizeFixed && nextPos + key.length + 14> BLOCK_SIZE) {
      byte[] blockNumsBytes = Util.int2byte(blockNums);
      System.arraycopy(blockNumsBytes, 0, bucket, 0, 4);

      cache.writeBlock(indexFile, blockNo, bucket);

      Arrays.fill(bucket, (byte) 0);
      System.arraycopy(Util.int2byte(8), 0, bucket, 4, 4);
      nextPos = 8;

      blockNo = blockNums;
      blockNums++;
    }

    // Now bucket has enough space to add entry

    // write key size if need
    if (!keySizeFixed) {
      byte[] keySizeBytes = Util.short2byte(key.length);
      System.arraycopy(keySizeBytes, 0, bucket, nextPos, 2);
      nextPos += 2;
    }

    // key
    int keyLen = keySizeFixed ? KEY_SIZE : key.length;
    System.arraycopy(key, 0, bucket, nextPos, keyLen);
    nextPos += keyLen;

    // fileId
    byte[] fileIdBytes = Util.int2byte(fileId);
    System.arraycopy(fileIdBytes, 0, bucket, nextPos, 4);
    nextPos += 4;

    // fileOffset
    byte[] fileOffsetBytes = Util.long2byte(fileOffset);
    System.arraycopy(fileOffsetBytes, 0, bucket, nextPos, 8);
    nextPos += 8;

    // current size of block
    byte[] offsetBytes = Util.int2byte(nextPos);
    System.arraycopy(offsetBytes, 0, bucket, 4, 4);

    cache.writeBlock(indexFile, blockNo, bucket);
  }

  public Tuple get(byte[] key) throws Exception {
//    if (key.length != KEY_SIZE)
//      throw new Exception();

    byte[] bucket = new byte[BLOCK_SIZE];
    int blockNo = keyHashCode(key);  // current blockNo
    while (true) {
      cache.readBlock(indexFile, blockNo, bucket);
      int size = Util.byte2int(bucket, 4);
      if (size == 0) size = 8;

      if (keySizeFixed) {
        for (int off = 8; off + ENTRY_SIZE <= size; off += ENTRY_SIZE) {
          if (Util.bytesEqual(bucket, off, key, 0, KEY_SIZE)) {  // find
            int fileId = Util.byte2int(bucket, off + KEY_SIZE);
            long fileOffset = Util.byte2int(bucket, off + KEY_SIZE + 4);
            //System.out.println("get " + Util.byte2int(key) + ' ' + fileId + ' ' + fileOffset);
            return new Tuple(dataFiles.get(fileId), fileOffset);
          }
        }
      } else {
        int off = 8;
        while (true) {
          int keyLen = Util.byte2short(bucket, off);
          if (key.length == keyLen && )
        }
      }
      blockNo = Util.byte2int(bucket, 0);
      if (blockNo == 0) {
        //System.out.println("get " + Util.byte2int(key) + ' ' + "fail");
        return null;
      }
    }
  }

  private byte[] getData(int fileId, long offset) {

    return null;
  }

  private int keyHashCode(byte[] key) {
    int h = 0;
    for (byte b : key) {
      h = 31 * h + b;
    }
    return Math.abs(h) % SIZE;
  }
}
