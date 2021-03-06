/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.async.client.gridfs

import com.mongodb.MongoGridFSException
import com.mongodb.async.client.FunctionalSpecification
import com.mongodb.async.client.MongoClients
import com.mongodb.async.client.MongoCollection
import com.mongodb.async.client.MongoDatabase
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import org.bson.BsonDocument
import org.bson.Document
import org.bson.UuidRepresentation
import org.bson.codecs.UuidCodec
import org.bson.types.ObjectId
import spock.lang.Unroll

import java.nio.ByteBuffer
import java.security.MessageDigest

import static GridFSTestHelper.run
import static com.mongodb.async.client.Fixture.getDefaultDatabaseName
import static com.mongodb.async.client.Fixture.getMongoClient
import static com.mongodb.async.client.gridfs.helpers.AsyncStreamHelper.toAsyncInputStream
import static com.mongodb.async.client.gridfs.helpers.AsyncStreamHelper.toAsyncOutputStream
import static com.mongodb.client.model.Filters.eq
import static org.bson.codecs.configuration.CodecRegistries.fromCodecs
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries

class GridFSBucketSmokeTestSpecification extends FunctionalSpecification {
    protected MongoDatabase mongoDatabase;
    protected MongoCollection<Document> filesCollection;
    protected MongoCollection<Document> chunksCollection;
    protected GridFSBucket gridFSBucket;
    def singleChunkString = 'GridFS'
    def multiChunkString = singleChunkString.padLeft(1024 * 255 * 5)

    def setup() {
        mongoDatabase = getMongoClient().getDatabase(getDefaultDatabaseName())
        filesCollection = mongoDatabase.getCollection('fs.files')
        chunksCollection = mongoDatabase.getCollection('fs.chunks')
        run(filesCollection.&drop)
        run(chunksCollection.&drop)
        gridFSBucket = new GridFSBucketImpl(mongoDatabase)
    }

    def cleanup() {
        if (filesCollection != null) {
            run(filesCollection.&drop)
            run(chunksCollection.&drop)
        }
    }

    @Unroll
    def 'should round trip a #description'() {
        given:
        def content = multiChunk ? multiChunkString : singleChunkString
        def contentBytes = content as byte[]
        def expectedLength = contentBytes.length
        def expectedMD5 = MessageDigest.getInstance('MD5').digest(contentBytes).encodeHex().toString()
        ObjectId fileId

        when:
        if (direct) {
            fileId = run(gridFSBucket.&uploadFromStream, 'myFile', toAsyncInputStream(content.getBytes()));
        } else {
            def outputStream = gridFSBucket.openUploadStream('myFile')
            run(outputStream.&write, ByteBuffer.wrap(contentBytes))
            run(outputStream.&close)
            fileId = outputStream.getFileId()
        }

        then:
        run(filesCollection.&count) == 1
        run(chunksCollection.&count) == chunkCount

        when:
        def fileInfo = run(gridFSBucket.find().filter(eq('_id', fileId)).&first)

        then:
        fileInfo.getId().getValue() == fileId
        fileInfo.getChunkSize() == gridFSBucket.getChunkSizeBytes()
        fileInfo.getLength() == expectedLength
        fileInfo.getMD5() == expectedMD5
        fileInfo.getMetadata() == null

        when:
        def byteBuffer = ByteBuffer.allocate(fileInfo.getLength() as int)
        if (direct) {
            run(gridFSBucket.openDownloadStream(fileId).&read, byteBuffer)
        } else {
            def outputStream = toAsyncOutputStream(byteBuffer)
            run(gridFSBucket.&downloadToStream, fileId, outputStream)
            run(outputStream.&close)
        }

        then:
        byteBuffer.array() == contentBytes

        where:
        description              | multiChunk | chunkCount | direct
        'a small file directly'  | false      | 1          | true
        'a small file to stream' | false      | 1          | false
        'a large file directly'  | true       | 5          | true
        'a large file to stream' | true       | 5          | false
    }

    def 'should round trip with a batchSize of 1'() {
        given:
        def content = multiChunkString
        def contentBytes = content as byte[]
        def expectedLength = contentBytes.length as Long
        def expectedMD5 = MessageDigest.getInstance('MD5').digest(contentBytes).encodeHex().toString()
        ObjectId fileId

        when:
        fileId = run(gridFSBucket.&uploadFromStream, 'myFile', toAsyncInputStream(content.getBytes()));

        then:
        run(filesCollection.&count) == 1
        run(chunksCollection.&count) == 5

        when:
        def fileInfo = run(gridFSBucket.find().filter(eq('_id', fileId)).&first)

        then:
        fileInfo.getId().getValue() == fileId
        fileInfo.getChunkSize() == gridFSBucket.getChunkSizeBytes()
        fileInfo.getLength() == expectedLength
        fileInfo.getMD5() == expectedMD5
        fileInfo.getMetadata() == null

        when:
        def byteBuffer = ByteBuffer.allocate(fileInfo.getLength() as int)
        run(gridFSBucket.openDownloadStream(fileId).batchSize(1).&read, byteBuffer)

        then:
        byteBuffer.array() == contentBytes
    }

    def 'should use custom uploadOptions when uploading' () {
        given:
        def chunkSize = 20
        def metadata = new Document('archived', false)
        def options = new GridFSUploadOptions()
                .chunkSizeBytes(chunkSize)
                .metadata(metadata)
        def content = 'qwerty' * 1024
        def contentBytes = content as byte[]
        def expectedLength = contentBytes.length as Long
        def expectedNoChunks = Math.ceil((expectedLength as double) / chunkSize) as int
        def expectedMD5 = MessageDigest.getInstance('MD5').digest(contentBytes).encodeHex().toString()
        ObjectId fileId

        when:
        if (direct) {
            fileId = run(gridFSBucket.&uploadFromStream, 'myFile', toAsyncInputStream(content.getBytes()), options);
        } else {
            def outputStream = gridFSBucket.openUploadStream('myFile', options)
            run(outputStream.&write, ByteBuffer.wrap(contentBytes))
            run(outputStream.&close)
            fileId = outputStream.getFileId()
        }

        then:
        run(filesCollection.&count) == 1
        run(chunksCollection.&count) == expectedNoChunks

        when:
        def fileInfo = run(gridFSBucket.find().filter(eq('_id', fileId)).&first)

        then:
        fileInfo.getId().getValue() == fileId
        fileInfo.getChunkSize() == options.getChunkSizeBytes()
        fileInfo.getLength() == expectedLength
        fileInfo.getMD5() == expectedMD5
        fileInfo.getMetadata() == options.getMetadata()

        when:
        def byteBuffer = ByteBuffer.allocate(fileInfo.getLength() as int)
        if (direct) {
            run(gridFSBucket.openDownloadStream(fileId).&read, byteBuffer)
        } else {
            def outputStream = toAsyncOutputStream(byteBuffer)
            run(gridFSBucket.&downloadToStream, fileId, outputStream)
            run(outputStream.&close)
        }

        then:
        byteBuffer.array() == contentBytes

        where:
        direct << [true, false]
    }

    def 'should be able to open by name'() {
        given:
        def content = 'Hello GridFS'
        def contentBytes = content as byte[]
        def filename = 'myFile'
        def objectId = run(gridFSBucket.&uploadFromStream, filename, toAsyncInputStream(content.getBytes()))
        def fileInfo = run(gridFSBucket.find(new Document('_id', objectId)).&first)

        when:
        def byteBuffer = ByteBuffer.allocate(fileInfo.getLength() as int)
        if (direct) {
            run(gridFSBucket.openDownloadStreamByName(filename).&read, byteBuffer)
        } else {
            def outputStream = toAsyncOutputStream(byteBuffer)
            run(gridFSBucket.&downloadToStreamByName, filename, outputStream)
            run(outputStream.&close)
        }

        then:
        byteBuffer.array() == contentBytes

        where:
        direct << [true, false]
    }

    def 'should be able to handle missing file'() {
        when:
        def filename = 'myFile'
        def byteBuffer = ByteBuffer.allocate(10)
        if (direct) {
            run(gridFSBucket.openDownloadStreamByName(filename).&read, byteBuffer)
        } else {
            def outputStream = toAsyncOutputStream(byteBuffer)
            run(gridFSBucket.&downloadToStreamByName, filename, outputStream)
            run(outputStream.&close)
        }

        then:
        thrown(MongoGridFSException)

        where:
        direct << [true, false]
    }

    def 'should abort and cleanup'() {
        when:
        def contentBytes = multiChunkString as byte[]

        then:
        run(filesCollection.&count) == 0

        when:
        def outputStream = gridFSBucket.openUploadStream('myFile')
        run(outputStream.&write, ByteBuffer.wrap(contentBytes))
        run(outputStream.&abort)

        then:
        run(filesCollection.&count) == 0
        run(chunksCollection.&count) == 0
    }

    def 'should create the indexes as expected'() {
        when:
        def filesIndexKey = Document.parse('{ filename: 1, uploadDate: 1 }')
        def chunksIndexKey = Document.parse('{ files_id: 1, n: 1 }')

        then:
        !run(filesCollection.listIndexes().&into, [])*.get('key').contains(filesIndexKey)
        !run(chunksCollection.listIndexes().&into, [])*.get('key').contains(chunksIndexKey)

        when:
        run(gridFSBucket.&uploadFromStream, 'myFile', toAsyncInputStream(multiChunkString.getBytes()))

        then:
        run(filesCollection.listIndexes().&into, [])*.get('key').contains(Document.parse('{ filename: 1, uploadDate: 1 }'))
        run(chunksCollection.listIndexes().&into, [])*.get('key').contains(Document.parse('{ files_id: 1, n: 1 }'))
    }

    def 'should use the user provided codec registries for encoding / decoding data'() {
        given:
        def codecRegistry = fromRegistries(fromCodecs(new UuidCodec(UuidRepresentation.STANDARD)), MongoClients.getDefaultCodecRegistry())
        def database = getMongoClient().getDatabase(getDefaultDatabaseName()).withCodecRegistry(codecRegistry)
        def uuid = UUID.randomUUID()
        def fileMeta = new Document('uuid', uuid)
        def gridFSBucket = GridFSBuckets.create(database)

        when:
        def fileId = run(gridFSBucket.&uploadFromStream, 'myFile', toAsyncInputStream(multiChunkString.getBytes()),
                new GridFSUploadOptions().metadata(fileMeta))

        def file = run(gridFSBucket.find(new Document('_id', fileId)).&first)

        then:
        file.getMetadata() == fileMeta

        when:
        def fileAsDocument = run(filesCollection.find(BsonDocument).&first)

        then:
        fileAsDocument.getDocument('metadata').getBinary('uuid').getType() == 4 as byte
    }
}

