package com.example.demo.rest.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class MediaStreamLoaderImpl implements MediaStreamLoader {

    private static final Logger logger = LoggerFactory.getLogger(MediaStreamLoaderImpl.class);
    private static final int BUFFER_SIZE = 8192;

    @Override
    public ResponseEntity<StreamingResponseBody> loadEntireMediaFile(String localMediaFilePath) throws IOException {
        Path filePath = Paths.get(localMediaFilePath);
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("The media file does not exist.");
        }
        long fileSize = Files.size(filePath);
        long endPos = fileSize > 0 ? fileSize - 1 : 0;
        return loadPartialMediaFile(localMediaFilePath, 0, endPos);
    }

    @Override
    public ResponseEntity<StreamingResponseBody> loadPartialMediaFile(String localMediaFilePath, String rangeValues) throws IOException {
        if (!StringUtils.hasText(rangeValues)) {
            logger.debug("No range specified, streaming entire file.");
            return loadEntireMediaFile(localMediaFilePath);
        }

        if (!StringUtils.hasText(localMediaFilePath)) {
            throw new IllegalArgumentException("The full path to the media file is NULL or empty.");
        }

        Path filePath = Paths.get(localMediaFilePath);
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("The media file does not exist.");
        }

        long fileSize = Files.size(filePath);
        long rangeStart = 0L;
        long rangeEnd = fileSize > 0 ? fileSize - 1 : 0L;

        logger.debug("Parsing range header: [{}]", rangeValues);
        String[] rangesArr = rangeValues.replace("bytes=", "").split("-");
        try {
            if (rangesArr.length > 0 && StringUtils.hasText(rangesArr[0])) {
                rangeStart = safeParseStringValuetoLong(numericStringValue(rangesArr[0]), 0L);
            }
            if (rangesArr.length > 1 && StringUtils.hasText(rangesArr[1])) {
                rangeEnd = safeParseStringValuetoLong(numericStringValue(rangesArr[1]), rangeEnd);
            }
        } catch (Exception e) {
            logger.warn("Invalid range format: [{}]", rangeValues, e);
        }

        if (rangeEnd == 0L && fileSize > 0L) {
            rangeEnd = fileSize - 1;
        }
        if (fileSize < rangeEnd) {
            rangeEnd = fileSize - 1;
        }
        if (rangeStart > rangeEnd) {
            rangeStart = rangeEnd;
        }

        logger.debug("Parsed range: {}-{}", rangeStart, rangeEnd);
        return loadPartialMediaFile(localMediaFilePath, rangeStart, rangeEnd);
    }

    @Override
    public ResponseEntity<StreamingResponseBody> loadPartialMediaFile(String localMediaFilePath, long fileStartPos, long fileEndPos) throws IOException {
        Path filePath = Paths.get(localMediaFilePath);
        if (!Files.exists(filePath)) {
            throw new FileNotFoundException("The media file does not exist.");
        }

        long fileSize = Files.size(filePath);
        if (fileStartPos < 0L) fileStartPos = 0L;
        if (fileStartPos >= fileSize && fileSize > 0L) fileStartPos = fileSize - 1L;
        if (fileEndPos >= fileSize && fileSize > 0L) fileEndPos = fileSize - 1L;
        if (fileSize == 0L) {
            fileStartPos = 0L;
            fileEndPos = 0L;
        }
        if (fileStartPos > fileEndPos) fileStartPos = fileEndPos;

        String mimeType = Files.probeContentType(filePath);
        if (mimeType == null) mimeType = "application/octet-stream";

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, mimeType);
        headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf((fileEndPos - fileStartPos) + 1));
        headers.add(HttpHeaders.CONTENT_RANGE, String.format("bytes %d-%d/%d", fileStartPos, fileEndPos, fileSize));

        final long start = fileStartPos;
        final long end = fileEndPos;
        StreamingResponseBody responseStream = outputStream -> {
            try (RandomAccessFile file = new RandomAccessFile(localMediaFilePath, "r")) {
                file.seek(start);
                byte[] buffer = new byte[BUFFER_SIZE];
                long bytesToRead = end - start + 1;
                int len;
                while (bytesToRead > 0 && (len = file.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                    outputStream.write(buffer, 0, len);
                    bytesToRead -= len;
                }
                outputStream.flush();
            } catch (Exception e) {
                logger.error("Error streaming file: {}", localMediaFilePath, e);
                throw e;
            }
        };

        HttpStatus status = (fileStartPos == 0 && fileEndPos == fileSize - 1) ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;
        return new ResponseEntity<>(responseStream, headers, status);
    }

    private long safeParseStringValuetoLong(String valToParse, long defaultVal) {
        if (StringUtils.hasText(valToParse)) {
            try {
                return Long.parseLong(valToParse);
            } catch (NumberFormatException ex) {
                logger.warn("Invalid long value: [{}]", valToParse);
            }
        }
        return defaultVal;
    }

    private String numericStringValue(String origVal) {
        if (StringUtils.hasText(origVal)) {
            String retVal = origVal.replaceAll("[^0-9]", "");
            logger.debug("Parsed numeric value: [{}]", retVal);
            return retVal;
        }
        return "";
    }
}