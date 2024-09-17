/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.buffer.ByteBuffer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * The implementation of {@link ByteBuffer} for byte arrays.
 *
 * @param <T> The byte buffer type
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class ByteArrayByteBuffer<T> implements ByteBuffer<T> {
    private final byte[] underlyingBytes;
    private int readerIndex;
    private int writerIndex;

    public ByteArrayByteBuffer(byte[] underlyingBytes) {
        this(underlyingBytes, underlyingBytes.length);
    }

    public ByteArrayByteBuffer(byte[] underlyingBytes, int capacity) {
        if (capacity < underlyingBytes.length) {
            this.underlyingBytes = Arrays.copyOf(underlyingBytes, capacity);
        } else if (capacity > underlyingBytes.length) {
            this.underlyingBytes = new byte[capacity];
            System.arraycopy(underlyingBytes, 0, this.underlyingBytes, 0, underlyingBytes.length);
        } else {
            this.underlyingBytes = underlyingBytes;
        }

    }

    @Override
    public T asNativeBuffer() {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public int readableBytes() {
        return this.underlyingBytes.length - this.readerIndex;
    }

    @Override
    public int writableBytes() {
        return this.underlyingBytes.length - this.writerIndex;
    }

    @Override
    public int maxCapacity() {
        return this.underlyingBytes.length;
    }

    @Override
    public ByteBuffer capacity(int capacity) {
        return new ByteArrayByteBuffer<>(this.underlyingBytes, capacity);
    }

    @Override
    public int readerIndex() {
        return this.readerIndex;
    }

    @Override
    public ByteBuffer readerIndex(int readPosition) {
        this.readerIndex = Math.min(readPosition, this.underlyingBytes.length - 1);
        return this;
    }

    @Override
    public int writerIndex() {
        return this.writerIndex;
    }

    @Override
    public ByteBuffer writerIndex(int position) {
        this.writerIndex = Math.min(position, this.underlyingBytes.length - 1);
        return this;
    }

    @Override
    public byte read() {
        return this.underlyingBytes[this.readerIndex++];
    }

    @Override
    public CharSequence readCharSequence(int length, Charset charset) {
        String s = new String(this.underlyingBytes, this.readerIndex, length, charset);
        this.readerIndex += length;
        return s;
    }

    @Override
    public ByteBuffer read(byte[] destination) {
        int count = Math.min(this.readableBytes(), destination.length);
        System.arraycopy(this.underlyingBytes, this.readerIndex, destination, 0, count);
        this.readerIndex += count;
        return this;
    }

    @Override
    public ByteBuffer read(byte[] destination, int offset, int length) {
        int count = Math.min(this.readableBytes(), Math.min(destination.length - offset, length));
        System.arraycopy(this.underlyingBytes, this.readerIndex, destination, offset, count);
        this.readerIndex += count;
        return this;
    }

    @Override
    public ByteBuffer write(byte b) {
        this.underlyingBytes[this.writerIndex++] = b;
        return this;
    }

    @Override
    public ByteBuffer write(byte[] source) {
        int count = Math.min(this.writableBytes(), source.length);
        System.arraycopy(source, 0, this.underlyingBytes, this.writerIndex, count);
        this.writerIndex += count;
        return this;
    }

    @Override
    public ByteBuffer write(CharSequence source, Charset charset) {
        this.write(source.toString().getBytes(charset));
        return this;
    }

    @Override
    public ByteBuffer write(byte[] source, int offset, int length) {
        int count = Math.min(this.writableBytes(), length);
        System.arraycopy(source, offset, this.underlyingBytes, this.writerIndex, count);
        this.writerIndex += count;
        return this;
    }

    @Override
    public ByteBuffer write(ByteBuffer... buffers) {
        ByteBuffer[] var2 = buffers;
        int var3 = buffers.length;

        for (int var4 = 0; var4 < var3; ++var4) {
            ByteBuffer<?> buffer = var2[var4];
            this.write(buffer.toByteArray());
        }

        return this;
    }

    @Override
    public ByteBuffer write(java.nio.ByteBuffer... buffers) {
        java.nio.ByteBuffer[] var2 = buffers;
        int var3 = buffers.length;

        for (int var4 = 0; var4 < var3; ++var4) {
            java.nio.ByteBuffer buffer = var2[var4];
            this.write(buffer.array());
        }

        return this;
    }

    @Override
    public ByteBuffer slice(int index, int length) {
        return new ByteArrayByteBuffer<>(Arrays.copyOfRange(this.underlyingBytes, index, index + length), length);
    }

    @Override
    public java.nio.ByteBuffer asNioBuffer() {
        return java.nio.ByteBuffer.wrap(this.underlyingBytes, this.readerIndex, this.readableBytes());
    }

    @Override
    public java.nio.ByteBuffer asNioBuffer(int index, int length) {
        return java.nio.ByteBuffer.wrap(this.underlyingBytes, index, length);
    }

    @Override
    public InputStream toInputStream() {
        return new ByteArrayInputStream(this.underlyingBytes, this.readerIndex, this.readableBytes());
    }

    @Override
    public OutputStream toOutputStream() {
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public byte[] toByteArray() {
        return Arrays.copyOfRange(this.underlyingBytes, this.readerIndex, this.readableBytes());
    }

    @Override
    public String toString(Charset charset) {
        return new String(this.underlyingBytes, this.readerIndex, this.readableBytes(), charset);
    }

    @Override
    public int indexOf(byte b) {
        for (int i = this.readerIndex; i < this.underlyingBytes.length; ++i) {
            if (this.underlyingBytes[i] == b) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public byte getByte(int index) {
        return this.underlyingBytes[index];
    }
}
