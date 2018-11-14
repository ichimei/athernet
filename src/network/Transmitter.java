package network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public class Transmitter {

	static int amp = 32767;        // amplitude
	static float fs = 44100;       // sample rate
	static float fc = 11025;       // frequency of carrier
	static int spb = 6;            // samples per bit
	static int trunk = 200;        // trunk size (bits per frame)
	static int lenHeader = 200;
	static int maxBuffer = 100;  // max size of buffer (flush if exceeded)
	static boolean debug = false;

	int bytesTrans = 0;    // number of bytes transmitted
	File input;
	FileInputStream fis;
	SourceDataLine speak;

	ByteArrayOutputStream bos = new ByteArrayOutputStream();

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100.f;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = false;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	private boolean[] readBits(int bits) {
		byte buffer[] = new byte[bits / 8];
		int bytesRead = 0;
		try {
			bytesRead = fis.read(buffer);
		} catch (IOException e) {
			e.printStackTrace();
		}
		boolean bitsBuffer[] = new boolean[bits];
		if (bytesRead == -1)
			return null;
		for (int i = 0; i < bytesRead; ++i) {
			byte b = buffer[i];
			for (int j = 0; j < 8; ++j) {
				bitsBuffer[i*8+j] = (b & 1) > 0;
				b >>= 1;
			}
		}
		for (int i = bytesRead; i < bits / 8 - bytesRead; ++i) {
			for (int j = 0; j < 8; ++j) {
				bitsBuffer[i*8+j] = false;
			}
		}
		return bitsBuffer;
	}

	private void writeBytes(byte bytes[]) {
		try {
			bos.write(bytes);
			bytesTrans += bytes.length;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Transmitter(File file) {
		input = file;
	}

	public void transmit() {
		try {

			int length = (int) input.length() * 8;
			fis = new FileInputStream(input);
			AudioFormat format = getAudioFormat();
			DataLine.Info infoSpeak = new DataLine.Info(SourceDataLine.class, format);
			speak = (SourceDataLine) AudioSystem.getLine(infoSpeak);

			byte intv[] = new byte[200];

			float fp[] = new float[lenHeader];
			float halfLenHeader = lenHeader / 2;
			for (int i = 0; i < lenHeader / 2; ++i)
				fp[i] = 2000.f + i / halfLenHeader * 8000.f;
			for (int i = lenHeader / 2; i < lenHeader; ++i)
				fp[i] = 10000.f - (i - lenHeader / 2) / halfLenHeader * 8000.f;

			byte header[] = new byte[2*lenHeader];
			float cum = 0.f;

			for (int i = 0; i < lenHeader; ++i) {
				cum += fp[i];
				int wave = (int) (amp * Math.sin(2 * Math.PI * cum / fs));
				header[2*i] = (byte) (wave & 0xff);
				header[2*i+1] = (byte) (wave >> 8);
			}

			byte wave[] = new byte[2*spb];

			for (int i = 0; i < 1000; ++i) {
				for (int k = 0; k < spb; ++k) {
					int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs));
					wave[2*k] = (byte) (waveo & 0xff);
					wave[2*k+1] = (byte) (waveo >> 8);
				}
				writeBytes(wave);
			}

			int maxTrunks = length / trunk + (length % trunk > 0 ? 1 : 0);

			for (int i = 0; i < maxTrunks; ++i) {
				boolean bitsBuffer[] = readBits(trunk);
				writeBytes(header);
				for (int j = 0; j < trunk; ++j) {
					for (int k = 0; k < spb; ++k) {
						int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (2 * (bitsBuffer[j] ? 1 : 0) - 1));
						wave[2*k] = (byte) (waveo & 0xff);
						wave[2*k+1] = (byte) (waveo >> 8);
					}
					writeBytes(wave);
				}
				writeBytes(intv);
			}

			for (int i = 0; i < 100000; ++i) {
				for (int k = 0; k < spb; ++k) {
					int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs));
					wave[2*k] = (byte) (waveo & 0xff);
					wave[2*k+1] = (byte) (waveo >> 8);
				}
				writeBytes(wave);
			}

			fis.close();
			byte data[] = bos.toByteArray();

			if (debug) {
				AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, data.length), AudioFileFormat.Type.WAVE, new File("INPUT2.wav"));
				return;
			}

			speak.open(format);
			speak.start();

			System.out.println("Start transmitting...");

			speak.write(data, 0, data.length);

			System.out.println("End transmitting!");
			System.out.println("Length: " + length);
			System.out.println("Transmitted: " + bytesTrans);

			speak.stop();
			speak.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
 	}

	public static void main(String[] args) {
		File file = new File("INPUT.bin");
		Transmitter tx = new Transmitter(file);
		tx.transmit();
	}

}
