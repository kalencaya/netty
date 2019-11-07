package io.netty.buffer;

import org.junit.Test;

import java.util.Arrays;

public class PoolChunkTest {

    @Test
    public void testCompleteBalanceTreeConstruct(){
        int length = 1<< 11;
        length = length << 1;
        System.out.println(length);
        int[] memoryMap = new int[length];
        int[] depthMap = new int[length];
        int index = 1;
        for (int i = 0; i <= 11; i++){
            int depth = 1 << i;
            for (int j = 0; i < depth; j++){
                memoryMap[index] = depth;
                depthMap[index] = depth;
                index++;
            }
        }

        System.out.println(Arrays.toString(memoryMap));
        System.out.println(Arrays.toString(depthMap));
    }

    @Test
    public void testOperator() {
        int d = 5;
        int id = 1;
        int initial = - (1 << d);
        System.out.println(initial);
        System.out.println(id & initial);
    }
}
