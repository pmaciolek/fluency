/*
 * Copyright 2018 Mitsunori Komatsu (komamitsu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.komamitsu.fluency.buffer;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.komamitsu.fluency.BufferFullException;
import org.komamitsu.fluency.EventTime;
import org.komamitsu.fluency.format.RequestOption;
import org.komamitsu.fluency.sender.Sender;
import org.komamitsu.fluency.transporter.Transporter;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Buffer
{
    private static final Logger LOG = LoggerFactory.getLogger(Buffer.class);
    private final ObjectMapper objectMapper;
    private final FileBackup fileBackup;
    private final Config config;

    private final Map<String, RetentionBuffer> retentionBuffers = new HashMap<String, RetentionBuffer>();
    private final LinkedBlockingQueue<TaggableBuffer> flushableBuffers = new LinkedBlockingQueue<TaggableBuffer>();
    private final Queue<TaggableBuffer> backupBuffers = new ConcurrentLinkedQueue<TaggableBuffer>();
    private final BufferPool bufferPool;

    protected Buffer(final Config config)
    {
        this.config = config;

        if (config.getFileBackupDir() != null) {
            fileBackup = new FileBackup(new File(config.getFileBackupDir()), this, config.getFileBackupPrefix());
        }
        else {
            fileBackup = null;
        }

        objectMapper = new ObjectMapper(new MessagePackFactory());
        List<Module> jacksonModules = config.getJacksonModules();
        for (Module module : jacksonModules) {
            objectMapper.registerModule(module);
        }

        if (config.getChunkInitialSize() > config.getChunkRetentionSize()) {
            LOG.warn("Initial Buffer Chunk Size ({}) shouldn't be more than Buffer Chunk Retention Size ({}) for better performance.",
                    config.getChunkInitialSize(), config.getChunkRetentionSize());
        }
        bufferPool = new BufferPool(
                config.getChunkInitialSize(), config.getMaxBufferSize(), config.jvmHeapBufferMode);
    }

    void init()
    {
        if (fileBackup != null) {
            for (FileBackup.SavedBuffer savedBuffer : fileBackup.getSavedFiles()) {
                savedBuffer.open(new FileBackup.SavedBuffer.Callback() {
                    @Override
                    public void process(List<String> params, FileChannel channel)
                    {
                        LOG.info("Loading buffer: params={}, buffer={}", params, channel);
                        loadBufferFromFile(params, channel);
                    }
                });
            }
        }
    }

    void saveBuffer(List<String> params, ByteBuffer buffer)
    {
        if (fileBackup == null) {
            return;
        }
        LOG.info("Saving buffer: params={}, buffer={}", params, buffer);
        fileBackup.saveBuffer(params, buffer);
    }

    public void flush(Transporter transporter, boolean force)
            throws IOException
    {
        LOG.trace("flush(): force={}, bufferUsage={}", force, getBufferUsage());
        flushInternal(transporter, force);
    }

    public void close()
    {
        try {
            LOG.debug("Saving all buffers");
            saveAllBuffersToFile();
        }
        catch (Exception e) {
            LOG.warn("Failed to save all buffers", e);
        }
        LOG.debug("Closing buffers");
        closeInternal();
    }

    private long getMaxSize()
    {
        return config.getMaxBufferSize();
    }

    public float getBufferUsage()
    {
        return (float) getAllocatedSize() / getMaxSize();
    }

    public void clearBackupFiles()
    {
        if (fileBackup != null) {
            for (FileBackup.SavedBuffer buffer : fileBackup.getSavedFiles()) {
                buffer.remove();
            }
        }
    }

    public long getMaxBufferSize()
    {
        return config.getMaxBufferSize();
    }

    public String getFileBackupPrefix()
    {
        return config.getFileBackupPrefix();
    }

    public String getFileBackupDir()
    {
        return config.getFileBackupDir();
    }

    public List<Module> getJacksonModules()
    {
        return Collections.unmodifiableList(config.getJacksonModules());
    }

    private RetentionBuffer prepareBuffer(String tag, int writeSize)
            throws BufferFullException
    {
        RetentionBuffer retentionBuffer = retentionBuffers.get(tag);
        if (retentionBuffer != null && retentionBuffer.getByteBuffer().remaining() > writeSize) {
            return retentionBuffer;
        }

        int existingDataSize = 0;
        int newBufferChunkRetentionSize;
        if (retentionBuffer == null) {
            newBufferChunkRetentionSize = config.getChunkInitialSize();
        }
        else{
            existingDataSize = retentionBuffer.getByteBuffer().position();
            newBufferChunkRetentionSize = (int) (retentionBuffer.getByteBuffer().capacity() * config.getChunkExpandRatio());
        }

        while (newBufferChunkRetentionSize < (writeSize + existingDataSize)) {
            newBufferChunkRetentionSize *= config.getChunkExpandRatio();
        }

        ByteBuffer acquiredBuffer = bufferPool.acquireBuffer(newBufferChunkRetentionSize);
        if (acquiredBuffer == null) {
            throw new BufferFullException("Buffer is full. config=" + config + ", bufferPool=" + bufferPool);
        }

        RetentionBuffer newBuffer = new RetentionBuffer(acquiredBuffer);
        if (retentionBuffer != null) {
            retentionBuffer.getByteBuffer().flip();
            newBuffer.getByteBuffer().put(retentionBuffer.getByteBuffer());
            newBuffer.getCreatedTimeMillis().set(System.currentTimeMillis());
            bufferPool.returnBuffer(retentionBuffer.getByteBuffer());
        }
        LOG.trace("prepareBuffer(): allocate a new buffer. tag={}, buffer={}", tag, newBuffer);

        retentionBuffers.put(tag, newBuffer);
        return newBuffer;
    }

    private void loadDataToRetentionBuffers(String tag, ByteBuffer src)
            throws IOException
    {
        synchronized (retentionBuffers) {
            RetentionBuffer buffer = prepareBuffer(tag, src.remaining());
            buffer.getByteBuffer().put(src);
            moveRetentionBufferIfNeeded(tag, buffer);
        }
    }

    protected void loadBufferFromFile(List<String> params, FileChannel channel)
    {
        if (params.size() != 1) {
            throw new IllegalArgumentException("The number of params should be 1: params=" + params);
        }
        String tag = params.get(0);

        try {
            MappedByteBuffer src = channel.map(FileChannel.MapMode.PRIVATE, 0, channel.size());
            loadDataToRetentionBuffers(tag, src);
        }
        catch (Exception e) {
            LOG.error("Failed to load data to flushableBuffers: params={}, channel={}", params, channel);
        }
    }

    private void saveBuffer(TaggableBuffer buffer)
    {
        saveBuffer(Collections.singletonList(buffer.getTag()), buffer.getByteBuffer());
    }

    protected void saveAllBuffersToFile()
            throws IOException
    {
        moveRetentionBuffersToFlushable(true);  // Just in case

        TaggableBuffer flushableBuffer;
        while ((flushableBuffer = flushableBuffers.poll()) != null) {
            saveBuffer(flushableBuffer);
        }
        while ((flushableBuffer = backupBuffers.poll()) != null) {
            saveBuffer(flushableBuffer);
        }
    }

    private void appendMapInternal(String tag, Object timestamp, Map<String, Object> data)
            throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        objectMapper.writeValue(outputStream, Arrays.asList(timestamp, data));
        outputStream.close();

        loadDataToRetentionBuffers(tag, ByteBuffer.wrap(outputStream.toByteArray()));
    }

    private void appendMessagePackMapValueInternal(String tag, Object timestamp, byte[] mapValue, int offset, int len)
            throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // 2 items array
        outputStream.write(0x92);
        objectMapper.writeValue(outputStream, timestamp);
        outputStream.write(mapValue, offset, len);
        outputStream.close();

        loadDataToRetentionBuffers(tag, ByteBuffer.wrap(outputStream.toByteArray()));
    }

    private void appendMessagePackMapValueInternal(String tag, Object timestamp, ByteBuffer mapValue)
            throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // 2 items array
        outputStream.write(0x92);
        objectMapper.writeValue(outputStream, timestamp);
        // TODO: Optimize
        while (mapValue.hasRemaining()) {
            outputStream.write(mapValue.get());
        }
        outputStream.close();

        loadDataToRetentionBuffers(tag, ByteBuffer.wrap(outputStream.toByteArray()));
    }

    public void append(String tag, long timestamp, Map<String, Object> data)
            throws IOException
    {
        appendMapInternal(tag, timestamp, data);
    }

    public void append(String tag, EventTime timestamp, Map<String, Object> data)
            throws IOException
    {
        appendMapInternal(tag, timestamp, data);
    }

    public void appendMessagePackMapValue(String tag, long timestamp, byte[] mapValue, int offset, int len)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue, offset, len);
    }

    public void appendMessagePackMapValue(String tag, EventTime timestamp, byte[] mapValue, int offset, int len)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue, offset, len);
    }

    public void appendMessagePackMapValue(String tag, long timestamp, ByteBuffer mapValue)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue);
    }

    public void appendMessagePackMapValue(String tag, EventTime timestamp, ByteBuffer mapValue)
            throws IOException
    {
        appendMessagePackMapValueInternal(tag, timestamp, mapValue);

    }

    private void moveRetentionBufferIfNeeded(String tag, RetentionBuffer buffer)
            throws IOException
    {
        if (buffer.getByteBuffer().position() > config.getChunkRetentionSize()) {
            moveRetentionBufferToFlushable(tag, buffer);
        }
    }

    private void moveRetentionBuffersToFlushable(boolean force)
            throws IOException
    {
        long expiredThreshold = System.currentTimeMillis() - config.getChunkRetentionTimeMillis();

        synchronized (retentionBuffers) {
            for (Map.Entry<String, RetentionBuffer> entry : retentionBuffers.entrySet()) {
                // it can be null because moveRetentionBufferToFlushable() can set null
                if (entry.getValue() != null) {
                    if (force || entry.getValue().getCreatedTimeMillis().get() < expiredThreshold) {
                        moveRetentionBufferToFlushable(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
    }

    private void moveRetentionBufferToFlushable(String tag, RetentionBuffer buffer)
            throws IOException
    {
        try {
            LOG.trace("moveRetentionBufferToFlushable(): tag={}, buffer={}", tag, buffer);
            buffer.getByteBuffer().flip();
            flushableBuffers.put(new TaggableBuffer(tag, buffer.getByteBuffer()));
            retentionBuffers.put(tag, null);
        }
        catch (InterruptedException e) {
            throw new IOException("Failed to move retention buffer due to interruption", e);
        }
    }

    public void flushInternal(Transporter transporter, boolean force)
            throws IOException
    {
        moveRetentionBuffersToFlushable(force);

        TaggableBuffer flushableBuffer;
        while (!Thread.currentThread().isInterrupted() &&
                (flushableBuffer = flushableBuffers.poll()) != null) {
            boolean keepBuffer = false;
            try {
                LOG.trace("flushInternal(): bufferUsage={}, flushableBuffer={}", getBufferUsage(), flushableBuffer);
                String tag = flushableBuffer.getTag();
                ByteBuffer dataBuffer = flushableBuffer.getByteBuffer();
                transporter.transport(tag, dataBuffer);
                /*
                int dataLength = dataBuffer.limit();
                messagePacker.packArrayHeader(3);
                messagePacker.packString(tag);
                messagePacker.packRawStringHeader(dataLength);
                messagePacker.flush();

                ByteBuffer headerBuffer = ByteBuffer.wrap(header.toByteArray());

                try {
                    if (config.isAckResponseMode()) {
                        byte[] uuidBytes = UUID.randomUUID().toString().getBytes(CHARSET);
                        ByteBuffer optionBuffer = ByteBuffer.wrap(objectMapper.writeValueAsBytes(new RequestOption(dataLength, uuidBytes)));
                        List<ByteBuffer> buffers = Arrays.asList(headerBuffer, dataBuffer, optionBuffer);

                        synchronized (sender) {
                            sender.sendWithAck(buffers, uuidBytes);
                        }
                    } else {
                        ByteBuffer optionBuffer = ByteBuffer.wrap(objectMapper.writeValueAsBytes(new RequestOption(dataLength, null)));
                        List<ByteBuffer> buffers = Arrays.asList(headerBuffer, dataBuffer, optionBuffer);

                        synchronized (sender) {
                            sender.send(buffers);
                        }
                    }
                }
                catch (IOException e) {
                    LOG.warn("Failed to send data. The data is going to be saved into the buffer again: data={}", flushableBuffer);
                    keepBuffer = true;
                    throw e;
                }
                */
            }
            finally {
                if (keepBuffer) {
                    try {
                        flushableBuffers.put(flushableBuffer);
                    }
                    catch (InterruptedException e1) {
                        LOG.warn("Failed to save the data into the buffer. Trying to save it in extra buffer: chunk={}", flushableBuffer);
                        backupBuffers.add(flushableBuffer);
                    }
                }
                else {
                    bufferPool.returnBuffer(flushableBuffer.getByteBuffer());
                }
            }
        }
    }

    protected synchronized void closeInternal()
    {
        retentionBuffers.clear();
        bufferPool.releaseBuffers();
    }

    public long getAllocatedSize()
    {
        return bufferPool.getAllocatedSize();
    }

    public long getBufferedDataSize()
    {
        long size = 0;
        synchronized (retentionBuffers) {
            for (Map.Entry<String, RetentionBuffer> buffer : retentionBuffers.entrySet()) {
                if (buffer.getValue() != null && buffer.getValue().getByteBuffer() != null) {
                    size += buffer.getValue().getByteBuffer().position();
                }
            }
        }
        for (TaggableBuffer buffer : flushableBuffers) {
            if (buffer.getByteBuffer() != null) {
                size += buffer.getByteBuffer().remaining();
            }
        }
        return size;
    }

    public boolean getJvmHeapBufferMode()
    {
        return bufferPool.getJvmHeapBufferMode();
    }

    private static class RetentionBuffer
    {
        private final AtomicLong createdTimeMillis = new AtomicLong();
        private final ByteBuffer byteBuffer;

        RetentionBuffer(ByteBuffer byteBuffer)
        {
            this.byteBuffer = byteBuffer;
        }

        AtomicLong getCreatedTimeMillis()
        {
            return createdTimeMillis;
        }

        ByteBuffer getByteBuffer()
        {
            return byteBuffer;
        }

        @Override
        public String toString()
        {
            return "RetentionBuffer{" +
                    "createdTimeMillis=" + createdTimeMillis+
                    ", byteBuffer=" + byteBuffer +
                    '}';
        }
    }

    private static class TaggableBuffer
    {
        private final String tag;
        private final ByteBuffer byteBuffer;

        public TaggableBuffer(String tag, ByteBuffer byteBuffer)
        {
            this.tag = tag;
            this.byteBuffer = byteBuffer;
        }

        public String getTag()
        {
            return tag;
        }

        public ByteBuffer getByteBuffer()
        {
            return byteBuffer;
        }

        @Override
        public String toString()
        {
            return "TaggableBuffer{" +
                    "tag='" + tag + '\'' +
                    ", byteBuffer=" + byteBuffer +
                    '}';
        }
    }

    public int getChunkInitialSize()
    {
        return config.getChunkInitialSize();
    }

    public float getChunkExpandRatio()
    {
        return config.getChunkExpandRatio();
    }

    public int getChunkRetentionSize()
    {
        return config.getChunkRetentionSize();
    }

    public int getChunkRetentionTimeMillis()
    {
        return config.getChunkRetentionTimeMillis();
    }

    @Override
    public String toString()
    {
        return "PackedForwardBuffer{" +
                "retentionBuffers=" + retentionBuffers +
                ", flushableBuffers=" + flushableBuffers +
                ", backupBuffers=" + backupBuffers +
                ", bufferPool=" + bufferPool +
                ", config=" + config +
                "} " + super.toString();
    }

    public static class Config
        implements Instantiator
    {
        private long maxBufferSize = 512 * 1024 * 1024;
        private String fileBackupDir;
        private String fileBackupPrefix;  // Mainly for testing
        private List<Module> jacksonModules = Collections.emptyList();

        private int chunkInitialSize = 1024 * 1024;
        private float chunkExpandRatio = 2.0f;
        private int chunkRetentionSize = 4 * 1024 * 1024;
        private int chunkRetentionTimeMillis = 1000;
        private boolean jvmHeapBufferMode = false;

        public long getMaxBufferSize()
        {
            return maxBufferSize;
        }

        public Config setMaxBufferSize(long maxBufferSize)
        {
            this.maxBufferSize = maxBufferSize;
            return this;
        }

        public String getFileBackupDir()
        {
            return fileBackupDir;
        }

        public Config setFileBackupDir(String fileBackupDir)
        {
            this.fileBackupDir = fileBackupDir;
            return this;
        }

        public String getFileBackupPrefix()
        {
            return fileBackupPrefix;
        }

        public Config setFileBackupPrefix(String fileBackupPrefix)
        {
            this.fileBackupPrefix = fileBackupPrefix;
            return this;
        }


        public List<Module> getJacksonModules()
        {
            return jacksonModules;
        }

        public Config setJacksonModules(List<Module> jacksonModules)
        {
            this.jacksonModules = jacksonModules;
            return this;
        }

                public int getChunkInitialSize()
        {
            return chunkInitialSize;
        }

        public Config setChunkInitialSize(int chunkInitialSize)
        {
            this.chunkInitialSize = chunkInitialSize;
            return this;
        }

        public float getChunkExpandRatio()
        {
            return chunkExpandRatio;
        }

        public Config setChunkExpandRatio(float chunkExpandRatio)
        {
            this.chunkExpandRatio = chunkExpandRatio;
            return this;
        }

        public int getChunkRetentionSize()
        {
            return chunkRetentionSize;
        }

        public Config setChunkRetentionSize(int chunkRetentionSize)
        {
            this.chunkRetentionSize = chunkRetentionSize;
            return this;
        }

        public int getChunkRetentionTimeMillis()
        {
            return chunkRetentionTimeMillis;
        }

        public Config setChunkRetentionTimeMillis(int chunkRetentionTimeMillis)
        {
            this.chunkRetentionTimeMillis = chunkRetentionTimeMillis;
            return this;
        }

        public boolean getJvmHeapBufferMode()
        {
            return jvmHeapBufferMode;
        }

        public Config setJvmHeapBufferMode(boolean jvmHeapBufferMode)
        {
            this.jvmHeapBufferMode = jvmHeapBufferMode;
            return this;
        }

        @Override
        public String toString()
        {
            return "Config{" +
                    "maxBufferSize=" + maxBufferSize +
                    ", fileBackupDir='" + fileBackupDir + '\'' +
                    ", fileBackupPrefix='" + fileBackupPrefix + '\'' +
                    ", jacksonModules=" + jacksonModules +
                    ", chunkInitialSize=" + chunkInitialSize +
                    ", chunkExpandRatio=" + chunkExpandRatio +
                    ", chunkRetentionSize=" + chunkRetentionSize +
                    ", chunkRetentionTimeMillis=" + chunkRetentionTimeMillis +
                    ", jvmHeapBufferMode=" + jvmHeapBufferMode +
                    '}';
        }

        @Override
        public Buffer createInstance()
        {
            return new Buffer(this);
        }
    }

    public interface Instantiator
    {
        Buffer createInstance();
    }
}
