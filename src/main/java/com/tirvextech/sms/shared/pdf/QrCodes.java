package com.tirvextech.sms.shared.pdf;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Map;

/** PNG QR codes embedded in PDFs. */
public final class QrCodes {

	private QrCodes() {
	}

	public static byte[] png(String text, int size) {
		try {
			BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
					Map.of(EncodeHintType.MARGIN, 1));
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			MatrixToImageWriter.writeToStream(matrix, "PNG", out);
			return out.toByteArray();
		}
		catch (Exception ex) {
			throw new IllegalStateException("Could not create QR code", ex);
		}
	}
}