// License: GPL. For details, see LICENSE file.
// SPDX-License-Identifier: GPL-2.0-or-later
package org.openstreetmap.josm.plugins.photo_geotagging;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.common.RationalNumber;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffImageWriterLossy;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;

/**
 * Wrapper class for sanselan library
 */
public final class ExifGPSTagger {
    private ExifGPSTagger() {
        // Hide constructor
    }

    /**
     * Set the GPS values in JPEG EXIF metadata.
     * This is based on one of the examples of the sanselan project.
     *
     * @param imageFile A source image file.
     * @param dst The output file.
     * @param lat latitude
     * @param lon longitude
     * @param gpsTime time - can be null if not available
     * @param speed speed in km/h - can be null if not available
     * @param ele elevation - can be null if not available
     * @param imgDir image direction in degrees (0..360) - can be null if not available
     * @param lossy whether to use lossy approach when writing metadata (overwriting unknown tags)
     * @throws IOException in case of I/O error
     */
    public static void setExifGPSTag(File imageFile, File dst, double lat, double lon, Instant gpsTime, Double speed,
            Double ele, Double imgDir, boolean lossy) throws IOException {
        try {
            setExifGPSTagWorker(imageFile, dst, lat, lon, gpsTime, speed, ele, imgDir, lossy);
        } catch (ImagingException ire) {
            // This used to be two separate exceptions; ImageReadException and imageWriteException
            throw new IOException(tr("Read/write error: " + ire.getMessage()), ire);
        }
    }

    public static void setExifGPSTagWorker(File imageFile, File dst, double lat, double lon, Instant gpsTime, Double speed,
            Double ele, Double imgDir, boolean lossy) throws IOException {

        TiffOutputSet outputSet = null;
        ImageMetadata metadata = Imaging.getMetadata(imageFile);

        if (metadata instanceof JpegImageMetadata) {
            TiffImageMetadata exif = ((JpegImageMetadata) metadata).getExif();
            if (null != exif) {
                outputSet = exif.getOutputSet();
            }
        } else if (metadata instanceof TiffImageMetadata) {
            outputSet = ((TiffImageMetadata) metadata).getOutputSet();
        }

        if (null == outputSet) {
            outputSet = new TiffOutputSet();
        }

        TiffOutputDirectory gpsDirectory = outputSet.getOrCreateGpsDirectory();
        gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_VERSION_ID);
        gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_VERSION_ID, (byte)2, (byte)3, (byte)0, (byte)0);

        if (gpsTime != null) {
            Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            calendar.setTimeInMillis(gpsTime.toEpochMilli());

            final int year =   calendar.get(Calendar.YEAR);
            final int month =  calendar.get(Calendar.MONTH) + 1;
            final int day =    calendar.get(Calendar.DAY_OF_MONTH);
            final int hour =   calendar.get(Calendar.HOUR_OF_DAY);
            final int minute = calendar.get(Calendar.MINUTE);
            final int second = calendar.get(Calendar.SECOND);

            DecimalFormat yearFormatter = new DecimalFormat("0000");
            DecimalFormat monthFormatter = new DecimalFormat("00");
            DecimalFormat dayFormatter = new DecimalFormat("00");

            final String yearStr = yearFormatter.format(year);
            final String monthStr = monthFormatter.format(month);
            final String dayStr = dayFormatter.format(day);
            final String dateStamp = yearStr+":"+monthStr+":"+dayStr;
            //System.err.println("date: "+dateStamp+"  h/m/s: "+hour+"/"+minute+"/"+second);

            // make sure to remove old value if present (this method will
            // not fail if the tag does not exist).
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_TIME_STAMP);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_TIME_STAMP,
                    RationalNumber.valueOf(hour),
                    RationalNumber.valueOf(minute),
                    RationalNumber.valueOf(second));

            // make sure to remove old value if present (this method will
            // not fail if the tag does not exist).
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_DATE_STAMP);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_DATE_STAMP, dateStamp);
        }

        outputSet.setGpsInDegrees(lon, lat);

        if (speed != null) {
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_SPEED_REF);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_SPEED_REF,
                             GpsTagConstants.GPS_TAG_GPS_SPEED_REF_VALUE_KMPH);

            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_SPEED);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_SPEED, RationalNumber.valueOf(speed));
        }

        if (ele != null) {
            byte eleRef =  ele >= 0 ? (byte) 0 : (byte) 1;
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF, eleRef);

            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_ALTITUDE);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE, RationalNumber.valueOf(Math.abs(ele)));
        }

        if (imgDir != null) {
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF,
                             GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION_REF_VALUE_TRUE_NORTH);
            // make sure the value is in the range 0.0...<360.0
            if (imgDir < 0.0) {
                imgDir %= 360.0; // >-360.0...-0.0
                imgDir += 360.0; // >0.0...360.0
            }
            if (imgDir >= 360.0) {
                imgDir %= 360.0;
            }
            gpsDirectory.removeField(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION);
            gpsDirectory.add(GpsTagConstants.GPS_TAG_GPS_IMG_DIRECTION, RationalNumber.valueOf(imgDir));
        }

        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(dst))) {
            if (metadata instanceof JpegImageMetadata) {
                if (lossy) {
                    new ExifRewriter().updateExifMetadataLossy(imageFile, os, outputSet);
                } else {
                    new ExifRewriter().updateExifMetadataLossless(imageFile, os, outputSet);
                }
            } else if (metadata instanceof TiffImageMetadata) {
                new TiffImageWriterLossy().write(os, outputSet);
            }
        }
    }
}
