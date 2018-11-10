package network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public class Transmitter {

	static int amp = 32767;        // amplitude
	static float fs = 44100;       // sample rate
	static float fc = 11025;        // frequency of carrier
	static int spb = 44;           // samples per bit
	static int trunk = 100;        // trunk size (bits per frame)
	static int lenHeader = 440;
	static int maxBuffer = 44100;  // max size of buffer (flush if exceeded)

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

	private int readBits(byte buffer[]) {
		int bitsRead = 0;
		try {
			bitsRead = fis.read(buffer);
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (bitsRead == -1)
			return -1;
		for (int i = 0; i < bitsRead; ++i) {
			if (buffer[i] == '1')
				buffer[i] = 1;
			else
				buffer[i] = 0;
		}
		for (int i = bitsRead; i < buffer.length - bitsRead; ++i)
			buffer[i] = 0;
		return bitsRead;
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

			int length = (int) input.length();
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

			int tmpLength = length;
			byte lengthHeader[] = new byte[trunk];
			for (int i = 0; i < 32; ++i) {
				lengthHeader[i] = (byte) (tmpLength & 1);
				tmpLength >>= 1;
			}

			byte trunkBuffer[] = new byte[trunk];
			byte wave[] = new byte[2*spb];

			for (int i = 0; i < 100; ++i)
				writeBytes(intv);

			int maxTrunks = length / trunk + (length % trunk > 0 ? 1 : 0);

			for (int i = 0; i < maxTrunks; ++i) {
				readBits(trunkBuffer);
				writeBytes(header);
				for (int j = 0; j < trunk; ++j) {
					for (int k = 0; k < spb; ++k) {
						int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (2 * trunkBuffer[j] - 1));
						wave[2*k] = (byte) (waveo & 0xff);
						wave[2*k+1] = (byte) (waveo >> 8);
					}
					writeBytes(wave);
				}
				writeBytes(intv);
			}

			fis.close();

			byte data[] = bos.toByteArray();
			speak.open(format);
			speak.start();

			// AudioSystem.write(new AudioInputStream(bis, format, data.length), AudioFileFormat.Type.WAVE, new File("INPUT2.wav"));

			System.out.println("Start transmitting...");

			speak.write(data, 0, data.length);

			System.out.println("End transmitting!");
			System.out.println("Transmitted: " + bytesTrans);

			speak.stop();
			speak.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
 	}

	public static void main(String[] args) {
		File file = new File("INPUT.txt");
		Transmitter tx = new Transmitter(file);
		tx.transmit();
	}

}
