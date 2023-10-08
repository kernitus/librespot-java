/*
 * Copyright 2021 devgianlu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.gianlu.librespot.player.mixing;

import xyz.gianlu.librespot.player.mixing.output.OutputAudioFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * @author Gianlu
 */
class GainAwareCircularBuffer extends CircularBuffer {
    private final OutputAudioFormat format;
    GainAwareCircularBuffer(int bufferSize, OutputAudioFormat format) {
        super(bufferSize);
        this.format = format;
    }

    private static void writeToArray(int val, byte[] b, int dest, boolean isBigEndian) {
        if (val > 32767) val = 32767;
        else if (val < -32768) val = -32768;
        //else if (val < 0) val |= 32768;

        if(!isBigEndian) {
            b[dest] = (byte) val;
            b[dest + 1] = (byte) (val >>> 8);
        } else {
            b[dest] = (byte) (val >>> 8);
            b[dest + 1] = (byte) val;
        }
    }

    void readGain(byte[] b, int off, int len, float gain) {
        if (closed) return;

        lock.lock();

        try {
            awaitData(len);
            if (closed) return;

            final ByteBuffer buffer = ByteBuffer.allocate(2);
            buffer.order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            int dest = off;
            for (int i = 0; i < len; i += 2, dest += 2) {
                int val;
                if (format.isBigEndian()) {
                    val = (short) (((readInternal() & 0xFF) << 8) | (readInternal() & 0xFF));
                } else {
                    val = (short) ((readInternal() & 0xFF) | ((readInternal() & 0xFF) << 8));
                }

                val = (int) ((float) val * gain);
                writeToArray(val, b, dest, format.isBigEndian());
            }

            awaitSpace.signal();
        } catch (InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
    }

    void readMergeGain(byte[] b, int off, int len, float gg, float fg, float sg) {
        if (closed) return;

        lock.lock();

        try {
            awaitData(len);
            if (closed) return;

            int dest = off;
            for (int i = 0; i < len; i += 2, dest += 2) {
                short first;
                if (format.isBigEndian()){
                    first = (short) (((b[dest] & 0xFF) << 8) | (b[dest + 1] & 0xFF));
                } else {
                    first = (short) ((b[dest] & 0xFF) | ((b[dest + 1] & 0xFF) << 8));
                }
                first *= fg;

                int second;
                if (format.isBigEndian()) {
                    second = (short) (((readInternal() & 0xFF) << 8) | (readInternal() & 0xFF));
                } else {
                    second = (short) ((readInternal() & 0xFF) | ((readInternal() & 0xFF) << 8));
                }
                second *= sg;

                int result = first + second;
                result *= gg;
                writeToArray(result, b, dest, format.isBigEndian());
            }

            awaitSpace.signal();
        } catch (InterruptedException ignored) {
        } finally {
            lock.unlock();
        }
    }
}
