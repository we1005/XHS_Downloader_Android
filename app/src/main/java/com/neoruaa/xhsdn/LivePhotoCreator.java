package com.neoruaa.xhsdn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class LivePhotoCreator {
    private static final String TAG = "LivePhotoCreator";

    /**
     * Creates a live photo by embedding video into image with XMP metadata
     * @param imageFile The image file to use as the primary content
     * @param videoFile The video file to embed
     * @param outputFile The output live photo file
     * @param context Android context for MediaStore operations
     * @return True if successful, false otherwise
     */
    public static boolean createLivePhoto(File imageFile, File videoFile, File outputFile, Context context) {
        try {
            Log.d(TAG, "Creating live photo from image: " + imageFile.getAbsolutePath() + 
                   " (size: " + imageFile.length() + " bytes) and video: " + videoFile.getAbsolutePath() + 
                   " (size: " + videoFile.length() + " bytes) -> output: " + outputFile.getAbsolutePath());
            
            // Always convert image to JPEG format using BitmapFactory
            // This handles WebP, PNG, or any other format that Android can decode
            File jpegFile = new File(imageFile.getParentFile(), 
                imageFile.getName().replaceAll("\\.[^.]+$", "") + "_converted.jpg");
            
            Log.d(TAG, "Converting image to JPEG: " + jpegFile.getAbsolutePath());
            if (!convertToJpeg(imageFile, jpegFile)) {
                Log.e(TAG, "Failed to convert image to JPEG");
                return false;
            }
            Log.d(TAG, "Successfully converted to JPEG: " + jpegFile.getAbsolutePath() + " (size: " + jpegFile.length() + " bytes)");
            
            // Read the video file size
            long videoSize = videoFile.length();

            // For compatibility with working implementation, use video size for GCamera:MicroVideoOffset
            // Some parsers use fileLength - videoSize to locate video data
            String xmpDataStr = generateXMPMetadata((int)videoSize, (int)videoSize);
            byte[] xmpData = xmpDataStr.getBytes("UTF-8");
            byte[] xmpSegment = createXmpApp1Segment(xmpData);

            // Build a minimal EXIF App1 carrying the OPPO/ColorOS recognition stamp
            // (UserComment = "oplus_8388608"). Empirically this is the single
            // required signal for ColorOS 16 / OnePlus 15 to identify a third-party
            // live photo; without it the file is treated as a static image even
            // though the XMP MotionPhoto block is otherwise correct.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpegFile.getAbsolutePath(), bounds);
            byte[] exifSegment = createExifApp1Segment(
                    Math.max(bounds.outWidth, 1),
                    Math.max(bounds.outHeight, 1)
            );

            // Build the MPF (Multi-Picture Format) App2 segment. Together with
            // the oplus_8388608 UserComment, this is the segment layout that
            // matches what XHS's official app emits and what ColorOS 16
            // recognizes. The MPImageLength entry is patched in-place after
            // the JPEG portion is fully written.
            long primaryJpegLength =
                    2L                              // SOI
                    + exifSegment.length
                    + xmpSegment.length
                    + 74L                           // MPF segment size (fixed)
                    + (jpegFile.length() - 2L);     // rest of source JPEG after its own SOI
            byte[] mpfSegment = createMpfApp2Segment((int) primaryJpegLength);

            // Create the live photo using streaming approach to avoid memory issues
            boolean result = createLivePhotoStreaming(jpegFile, videoFile, outputFile, exifSegment, xmpSegment, mpfSegment);
            
            // Clean up temporary JPEG file
            if (jpegFile.exists()) {
                jpegFile.delete();
            }
            
            // Trigger MediaStore scan to ensure the file is properly indexed
            if (result && context != null) {
                triggerMediaStoreScan(context, outputFile);
            }
            
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating live photo: " + e.getMessage());
            e.printStackTrace();
            // If the file was created but is invalid, delete it
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        }
    }

    private static void triggerMediaStoreScan(Context context, File file) {
        MediaScannerConnection.scanFile(
            context,
            new String[]{file.getAbsolutePath()},
            new String[]{"image/jpeg"},  // MIME 类型
            (path, uri) -> Log.d(TAG, "Scanned: " + path + " -> " + uri)
        );
    }
    
    /**
     * Convert any image to JPEG format using Android's BitmapFactory
     * This handles WebP, PNG, JPEG (re-encode), and any other supported format
     */
    private static boolean convertToJpeg(File inputFile, File jpegFile) {
        Bitmap bitmap = null;
        Bitmap normalizedBitmap = null;
        try {
            // Decode the image using Android's BitmapFactory (supports many formats)
            bitmap = BitmapFactory.decodeFile(inputFile.getAbsolutePath());
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode image: " + inputFile.getAbsolutePath());
                return false;
            }

            normalizedBitmap = normalizeBitmapOrientation(inputFile, bitmap);
            Log.d(TAG, "Decoded image: " + bitmap.getWidth() + "x" + bitmap.getHeight() +
                    ", normalized: " + normalizedBitmap.getWidth() + "x" + normalizedBitmap.getHeight());

            // Compress as JPEG with high quality
            try (FileOutputStream fos = new FileOutputStream(jpegFile)) {
                return normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting to JPEG: " + e.getMessage());
            return false;
        } finally {
            if (normalizedBitmap != null && normalizedBitmap != bitmap && !normalizedBitmap.isRecycled()) {
                normalizedBitmap.recycle();
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private static Bitmap normalizeBitmapOrientation(File inputFile, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(inputFile.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_UNDEFINED ||
                    orientation == ExifInterface.ORIENTATION_NORMAL) {
                return bitmap;
            }

            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.setScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.setRotate(180f);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.setScale(1f, -1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.setRotate(90f);
                    matrix.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.setRotate(90f);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.setRotate(270f);
                    matrix.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.setRotate(270f);
                    break;
                default:
                    return bitmap;
            }

            Bitmap normalizedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    matrix,
                    true
            );
            Log.d(
                    TAG,
                    "Applied EXIF orientation " + orientation +
                            " (rotation=" + ImageOrientationUtils.rotationDegrees(orientation) +
                            ", swapsDimensions=" + ImageOrientationUtils.swapsWidthAndHeight(orientation) + ")"
            );
            return normalizedBitmap;
        } catch (IOException e) {
            Log.w(TAG, "Failed to read EXIF orientation, using original bitmap: " + e.getMessage());
            return bitmap;
        }
    }
    
    /**
     * Check if a file is in WebP format by reading its magic bytes
     * WebP files start with "RIFF" followed by file size and "WEBP"
     */
    private static boolean isWebPFormat(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[12];
            int bytesRead = fis.read(header);
            if (bytesRead < 12) {
                return false;
            }
            // Check for RIFF header and WEBP signature
            boolean isRiff = header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F';
            boolean isWebP = header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            return isRiff && isWebP;
        } catch (Exception e) {
            Log.e(TAG, "Error checking WebP format: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Convert a WebP image to JPEG format
     */
    private static boolean convertWebPToJpeg(File webpFile, File jpegFile) {
        try {
            // Decode WebP using Android's BitmapFactory
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(webpFile.getAbsolutePath());
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode WebP image");
                return false;
            }
            
            // Compress as JPEG with high quality
            try (FileOutputStream fos = new FileOutputStream(jpegFile)) {
                boolean success = bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
                bitmap.recycle();
                return success;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting WebP to JPEG: " + e.getMessage());
            return false;
        }
    }
    

    
    /**
     * Generates XMP metadata for live photo.
     *
     * This matches the byte pattern of the XMP that XHS's official app
     * emits and that ColorOS 16 / OnePlus 15 recognizes as 实况照片:
     *   - 4 namespaces only (no MiCamera, no MiCamera:XMPMeta blob)
     *   - no GCamera:MicroVideo* legacy fields
     *   - Primary container item carries only Mime + Semantic
     *     (no Item:Length / Item:Padding)
     *
     * @param videoSize The size of the embedded video in bytes
     * @param videoLengthForOffset Unused; kept for caller signature compatibility
     * @return XMP metadata string
     */
    private static String generateXMPMetadata(int videoSize, int videoLengthForOffset) {
        return String.format(
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">\n" +
            "  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "    <rdf:Description rdf:about=\"\"\n" +
            "        xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
            "        xmlns:OpCamera=\"http://ns.oplus.com/photos/1.0/camera/\"\n" +
            "        xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
            "        xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
            "      GCamera:MotionPhoto=\"1\"\n" +
            "      GCamera:MotionPhotoVersion=\"1\"\n" +
            "      GCamera:MotionPhotoPresentationTimestampUs=\"0\"\n" +
            "      OpCamera:MotionPhotoPrimaryPresentationTimestampUs=\"0\"\n" +
            "      OpCamera:MotionPhotoOwner=\"xhs\"\n" +
            "      OpCamera:OLivePhotoVersion=\"2\"\n" +
            "      OpCamera:VideoLength=\"%d\">\n" +
            "      <Container:Directory>\n" +
            "        <rdf:Seq>\n" +
            "          <rdf:li rdf:parseType=\"Resource\">\n" +
            "            <Container:Item\n" +
            "              Item:Mime=\"image/jpeg\"\n" +
            "              Item:Semantic=\"Primary\"/>\n" +
            "          </rdf:li>\n" +
            "          <rdf:li rdf:parseType=\"Resource\">\n" +
            "            <Container:Item\n" +
            "              Item:Mime=\"video/mp4\"\n" +
            "              Item:Semantic=\"MotionPhoto\"\n" +
            "              Item:Length=\"%d\"/>\n" +
            "          </rdf:li>\n" +
            "        </rdf:Seq>\n" +
            "      </Container:Directory>\n" +
            "    </rdf:Description>\n" +
            "  </rdf:RDF>\n" +
            "</x:xmpmeta>",
            videoSize, videoSize
        );
    }
    
    /**
     * Builds a minimal EXIF App1 segment carrying
     * {@code ExifIFD.UserComment = "oplus_8388608"} (the recognition stamp
     * ColorOS 16 / OnePlus 15 looks for on third-party live photos), plus
     * placeholder IFD0 entries (ImageWidth/ImageHeight/Orientation).
     *
     * Layout (all big-endian):
     *   FFE1 + segLen
     *   "Exif\0\0"
     *   TIFF header: "MM" 0x002A + IFD0_offset(8)
     *   IFD0: 4 entries (Width, Height, Orientation=0, ExifIFDPointer) + next=0
     *   ExifIFD: 2 entries (UserComment, LightSource=0) + next=0
     *   Data area: UserComment payload (ASCII charset id + "oplus_8388608")
     */
    private static byte[] createExifApp1Segment(int width, int height) throws IOException {
        byte[] userComment = "oplus_8388608".getBytes(StandardCharsets.US_ASCII);
        byte[] charsetId = new byte[]{'A', 'S', 'C', 'I', 'I', 0, 0, 0};
        byte[] ucBytes = new byte[charsetId.length + userComment.length];
        System.arraycopy(charsetId, 0, ucBytes, 0, charsetId.length);
        System.arraycopy(userComment, 0, ucBytes, charsetId.length, userComment.length);
        int ucCount = ucBytes.length;

        int ifd0Count = 4;
        int ifd0Size = 2 + ifd0Count * 12 + 4;          // 54
        int exifIfdOff = 8 + ifd0Size;                  // 62
        int exifCount = 2;
        int exifSize = 2 + exifCount * 12 + 4;          // 30
        int ucDataOff = exifIfdOff + exifSize;          // 92

        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(tiff);
        // TIFF header
        dos.writeShort(0x4D4D);                         // "MM" big-endian
        dos.writeShort(0x002A);                         // magic 42
        dos.writeInt(8);                                // IFD0 offset

        // IFD0
        dos.writeShort(ifd0Count);
        // ImageWidth (0x0100), LONG(4), count=1
        dos.writeShort(0x0100); dos.writeShort(4); dos.writeInt(1); dos.writeInt(width);
        // ImageHeight (0x0101), LONG(4), count=1
        dos.writeShort(0x0101); dos.writeShort(4); dos.writeInt(1); dos.writeInt(height);
        // Orientation (0x0112), SHORT(3), count=1, value packed into high 16 bits
        dos.writeShort(0x0112); dos.writeShort(3); dos.writeInt(1); dos.writeInt(0);
        // ExifIFDPointer (0x8769), LONG(4), count=1
        dos.writeShort(0x8769); dos.writeShort(4); dos.writeInt(1); dos.writeInt(exifIfdOff);
        dos.writeInt(0);                                // no IFD1

        // ExifIFD
        dos.writeShort(exifCount);
        // UserComment (0x9286), UNDEFINED(7), count=ucCount, offset=ucDataOff
        dos.writeShort(0x9286); dos.writeShort(7); dos.writeInt(ucCount); dos.writeInt(ucDataOff);
        // LightSource (0x9208), SHORT(3), count=1, value=0
        dos.writeShort(0x9208); dos.writeShort(3); dos.writeInt(1); dos.writeInt(0);
        dos.writeInt(0);                                // next IFD

        // Data area
        dos.write(ucBytes);

        byte[] tiffBytes = tiff.toByteArray();
        int payloadLen = 6 + tiffBytes.length;          // "Exif\0\0" + TIFF
        int segLen = payloadLen + 2;                    // includes the length field

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.write(0xFF); seg.write(0xE1);
        seg.write((segLen >> 8) & 0xFF); seg.write(segLen & 0xFF);
        seg.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        seg.write(tiffBytes);
        return seg.toByteArray();
    }

    /**
     * Build a Multi-Picture Format App2 segment with NumberOfImages = 1.
     * Layout matches the byte pattern observed in XHS's official app output.
     *
     * @param imageLength size of the primary JPEG (from SOI through EOI) in the
     *                    final output file. Stored in the single MPEntry's
     *                    image-size field.
     */
    private static byte[] createMpfApp2Segment(int imageLength) throws IOException {
        int segLen = 72;                                    // includes the 2-byte length field
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(tiff);
        dos.write(new byte[]{'M', 'P', 'F', 0});            // signature
        dos.writeShort(0x4D4D);                             // "MM" big-endian
        dos.writeShort(0x002A);                             // TIFF magic
        dos.writeInt(8);                                    // IFD0 offset
        dos.writeShort(3);                                  // 3 IFD entries
        // MPFVersion (0xB000), UNDEFINED(7), count=4, value inline "0100"
        dos.writeShort(0xB000); dos.writeShort(7); dos.writeInt(4); dos.write(new byte[]{'0','1','0','0'});
        // NumberOfImages (0xB001), LONG(4), count=1, value=1
        dos.writeShort(0xB001); dos.writeShort(4); dos.writeInt(1); dos.writeInt(1);
        // MPEntry (0xB002), UNDEFINED(7), count=16, offset=0x32
        dos.writeShort(0xB002); dos.writeShort(7); dos.writeInt(16); dos.writeInt(0x32);
        dos.writeInt(0);                                    // no IFD1
        // MPEntry data (16 bytes)
        dos.writeInt(0x00030000);                           // flags / image type
        dos.writeInt(imageLength);                          // primary image size
        dos.writeInt(0);                                    // offset = 0 (primary)
        dos.writeShort(0); dos.writeShort(0);               // dep1, dep2

        byte[] payload = tiff.toByteArray();

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.write(0xFF); seg.write(0xE2);
        seg.write((segLen >> 8) & 0xFF); seg.write(segLen & 0xFF);
        seg.write(payload);
        return seg.toByteArray();
    }

    /**
     * Creates an APP1 XMP segment with proper JPEG header
     * @param xmpData The XMP data as bytes
     * @return Byte array representing the APP1 XMP segment
     */
    private static byte[] createXmpApp1Segment(byte[] xmpData) throws IOException {
        // XMP header: "http://ns.adobe.com/xap/1.0/\0"
        byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/\0".getBytes("UTF-8");
        
        // Calculate total segment length: xmp header + xmp data + 2 bytes for length field
        int segmentLengthWithoutLengthField = xmpHeader.length + xmpData.length;
        int totalSegmentLength = segmentLengthWithoutLengthField + 2; // +2 for the length field itself
        
        // Create the segment
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        
        // Write APP1 marker (0xFFE1)
        segment.write(0xFF);
        segment.write(0xE1);
        
        // Write length field (2 bytes, big-endian)
        segment.write((totalSegmentLength >> 8) & 0xFF);
        segment.write(totalSegmentLength & 0xFF);
        
        // Write XMP header
        segment.write(xmpHeader);
        
        // Write XMP data
        segment.write(xmpData);
        
        return segment.toByteArray();
    }
    
    /**
     * Validates if the created live photo is valid
     * @param file The file to validate
     * @return true if valid, false otherwise
     */
    private static boolean isLivePhotoValid(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[10];
            int bytesRead = fis.read(header);
            if (bytesRead < 2) {
                return false;
            }
            
            // Check for JPEG SOI marker (0xFFD8)
            if (header[0] != (byte) 0xFF || header[1] != (byte) 0xD8) {
                Log.d(TAG, "File does not have valid JPEG SOI marker");
                return false;
            }
            
            // Check for the presence of XMP metadata by reading first part of file
            byte[] buffer = new byte[16384]; // Read first 16KB to look for XMP
            fis.close(); // Close the first stream
            
            // Read data to check for XMP metadata
            try (FileInputStream fis2 = new FileInputStream(file)) {
                int totalRead = 0;
                while (totalRead < buffer.length && (bytesRead = fis2.read(buffer, totalRead, buffer.length - totalRead)) != -1) {
                    totalRead += bytesRead;
                }
                
                String content = new String(buffer, 0, totalRead, "UTF-8");
                
                // Look for XMP signatures
                boolean hasXmpMeta = content.contains("xmpmeta");
                boolean hasMotionPhoto = content.contains("MotionPhoto");

                Log.d(TAG, "XMP validation - xmpmeta: " + hasXmpMeta + ", MotionPhoto: " + hasMotionPhoto);

                if (!hasXmpMeta || !hasMotionPhoto) {
                    Log.d(TAG, "File does not contain valid XMP Motion Photo metadata");
                    return false;
                }
            }
            
            // Try to decode the image to make sure it's valid
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.d(TAG, "Image has invalid dimensions: " + options.outWidth + "x" + options.outHeight);
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating live photo: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Reads a file into a byte array
     * @param file The file to read
     * @return Byte array containing the file contents
     * @throws IOException
     */
    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            
            int nRead;
            byte[] data = new byte[16384]; // 16KB buffer
            
            while ((nRead = fis.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            
            return buffer.toByteArray();
        }
    }
    
    /**
     * Creates a live photo using streaming approach to avoid memory issues
     * @param imageFile The image file to use as the primary content
     * @param videoFile The video file to embed
     * @param outputFile The output live photo file
     * @param exifSegment The EXIF App1 segment to insert (carries the ColorOS recognition stamp)
     * @param xmpSegment The XMP metadata segment to insert
     * @param mpfSegment The MPF App2 segment (Multi-Picture Format, NumberOfImages=1)
     * @return True if successful, false otherwise
     */
    private static boolean createLivePhotoStreaming(File imageFile, File videoFile, File outputFile, byte[] exifSegment, byte[] xmpSegment, byte[] mpfSegment) {
        try (FileInputStream imageStream = new FileInputStream(imageFile);
             FileInputStream videoStream = new FileInputStream(videoFile);
             FileOutputStream outputStream = new FileOutputStream(outputFile)) {

            // Write the JPEG header (first 2 bytes: SOI marker - 0xFFD8)
            byte[] headerBuffer = new byte[2];
            int bytesRead = imageStream.read(headerBuffer);
            if (bytesRead != 2) {
                Log.e(TAG, "Could not read image header");
                return false;
            }
            outputStream.write(headerBuffer);

            // Segment order must be SOI -> EXIF -> XMP -> MPF -> rest. The
            // EXIF UserComment="oplus_8388608" plus MPF declaration are what
            // ColorOS 16 / OnePlus 15 uses to recognize the file as 实况照片.
            outputStream.write(exifSegment);
            outputStream.write(xmpSegment);
            outputStream.write(mpfSegment);
            
            // Skip the first 2 bytes of the image (already written) and copy the rest
            // Copy remaining image data
            byte[] buffer = new byte[8192]; // 8KB buffer to reduce memory usage
            int totalImageBytes = (int)(imageFile.length() - 2); // Subtract the 2 bytes already read
            int copiedBytes = 0;
            
            while (copiedBytes < totalImageBytes) {
                int bytesToRead = Math.min(buffer.length, totalImageBytes - copiedBytes);
                bytesRead = imageStream.read(buffer, 0, bytesToRead);
                if (bytesRead == -1) break;
                
                outputStream.write(buffer, 0, bytesRead);
                copiedBytes += bytesRead;
            }
            
            // Copy the entire video file to the end
            long videoBytesCopied = 0;
            while (true) {
                bytesRead = videoStream.read(buffer);
                if (bytesRead == -1) break;
                
                outputStream.write(buffer, 0, bytesRead);
                videoBytesCopied += bytesRead;
            }
            
            outputStream.flush();
            
            Log.d(TAG, "Successfully created live photo with streaming approach. Image bytes copied: " + 
                  copiedBytes + ", Video bytes copied: " + videoBytesCopied + ", Total file size: " + 
                  outputFile.length());
            
            // Verify that the created file is valid
            if (!isLivePhotoValid(outputFile)) {
                Log.e(TAG, "Created live photo is not valid - failed validation check");
                if (outputFile.exists()) {
                    outputFile.delete(); // Clean up invalid file
                }
                return false;
            }
            
            Log.d(TAG, "Live photo validation passed successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error in streaming live photo creation: " + e.getMessage());
            e.printStackTrace();
            // If the file was created but is invalid, delete it
            if (outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        }
    }
}
