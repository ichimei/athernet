package network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

public class Receiver {

	static int amp = 32767;       // amplitude
	static float fs = 44100;      // sample rate
	static float fc = 11025;      // frequency of carrier
	static int spb = 44;          // samples per bit
	static int trunk = 200;       // trunk size (bits per frame)
	static int lenHeader = 440;
	static int maxBuffer = 100;
	static float thresPower = 10;
	static float thresPowerCoeff = 400;
	static int thresBack = 2000;

	byte data[];
	File output;
	File input = new File("INPUT2.wav");
	TargetDataLine mic;
	FileOutputStream fos;
	boolean stopped = true;
	boolean syncState = true;
	int bytesRecv = 0;
	ByteArrayOutputStream bos = new ByteArrayOutputStream();

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = false;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	public Receiver(File file) {
		output = file;
	}

	public void start() {
		stopped = false;
	}

	public void stop() {
		stopped = true;
	}

	private void writeBits(boolean bits[]) {
		try {
			byte bytes[] = new byte[bits.length / 8];
			for (int i = 0; i < bits.length / 8; ++i) {
				byte b = 0;
				for (int j = 0; j < 8; ++j) {
					if (bits[i*8+j]) {
						b |= 1 << j;
					}
				}
				bytes[i] = b;
			}
			fos.write(bytes);
			bytesRecv += bits.length / 8;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void receive() {
		try {

			fos = new FileOutputStream(output);
			AudioFormat format = getAudioFormat();
			DataLine.Info infoMic = new DataLine.Info(TargetDataLine.class, format);
			mic = (TargetDataLine) AudioSystem.getLine(infoMic);

			stopped = false;

			float fp[] = new float[lenHeader];
			float halfLenHeader = lenHeader / 2;
			for (int i = 0; i < lenHeader / 2; ++i)
				fp[i] = 2000.f + i / halfLenHeader * 8000.f;
			for (int i = lenHeader / 2; i < lenHeader; ++i)
				fp[i] = 10000.f - (i - lenHeader / 2) / halfLenHeader * 8000.f;

			int header[] = new int[lenHeader];
			float cum = 0.f;

			for (int i = 0; i < lenHeader; ++i) {
				cum += fp[i];
				header[i] = (int) (amp * Math.sin(2 * Math.PI * cum / fs));
			}

			byte empty[] = new byte[lenHeader];
			bos.write(empty);

			mic.open(format);
			mic.start();

			System.out.println("Start receiving...");

			byte buffer[] = new byte[maxBuffer];
			while (!stopped) {
				int bytesRead = mic.read(buffer, 0, maxBuffer);
				bos.write(buffer, 0, bytesRead);
			}

			System.out.println("End receiving!");

			mic.stop();
			mic.close();

			data = bos.toByteArray();

//			AudioInputStream ais = AudioSystem.getAudioInputStream(input);
//			data = ais.readAllBytes();

			int newData[] = new int[data.length / 2];

			for (int i = 0; i < newData.length; ++i) {
				newData[i] = (data[2*i+1] << 8) | (data[2*i] & 0xff);
				//System.out.println(newData[i]);
			}

			// System.out.println(data.length);

			float maxSyncPower = 0.f;
			int start = 0;
			float power = 0.f;

			boolean decoded[] = new boolean[trunk];

			for (int i = lenHeader; i < newData.length; ++i) {
				if (syncState) {
					power = power * 63.f / 64.f + ((float) newData[i] / amp) * ((float) newData[i] / amp) / 64.f;
					float syncPower = 0.f;
					for (int j = 0; j < lenHeader; ++j) {
						syncPower += ((float) header[j] / amp) * ((float) newData[i+j-lenHeader+1] / amp);
					}

					if (syncPower > power * thresPowerCoeff && syncPower > maxSyncPower && syncPower > thresPower) {
						maxSyncPower = syncPower;
						start = i;
					} else if (i - start > thresBack && start != 0) {
						// System.out.println(start);
						maxSyncPower = 0.f;
						syncState = false;
					}
				} else {
					if (i - start == spb * trunk) {
						for (int j = 0; j < trunk; ++j) {
							float sumRmCarr = 0.f;
							for (int k = 0; k < spb; ++k)
								sumRmCarr += (newData[start+1+j*spb+k]) * (float) Math.sin(2 * Math.PI * fc * k / fs);
							decoded[j] = sumRmCarr > 0.f;
						}
						writeBits(decoded);
						syncState = true;
						start = 0;
					}
				}
			}

			System.out.println("Received: " + bytesRecv);
			fos.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		long time = 10000;
		File file = new File("OUTPUT2.txt");
		Receiver rx = new Receiver(file);
		rx.start();
		new Thread(new Runnable() {
		     @Override
		     public void run() {
		    	 try {
					Thread.sleep(time);
					rx.stop();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
		     }
		}).start();
		rx.receive();
	}

}
