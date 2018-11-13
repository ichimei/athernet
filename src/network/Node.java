package network;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public class Node {

	final File file = new File("INPUT.bin");

	int mac_event_no;
	final int frames = 10;
	final int frame_size = 200;
	final int amp = 32767;        // amplitude
	final float fs = 44100;       // sample rate
	final float fc = 11025;       // frequency of carrier
	final int spb = 6;            // samples per bit
	final int header_size = 20;
	final int max_retry = 5;
	int retry = 0;
	int tx_lar = 0;
	int rx_lfr = 0;
	int num_frames = 0;
	int retry_cnt = 0;

	boolean tx_pending = true;
	byte[] received_frame;

	byte[] header = new byte[header_size*spb*2];
	byte[] intv = new byte[200];
	byte[][] frame_list;
	byte[] to_send = new byte[(header_size+frame_size)*spb*2];
	boolean[] tx_window;
	boolean[] rx_window;
	SourceDataLine speak;

	public Node() {
	}

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100.f;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = false;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	private void run() {
		frames_init(file);
		speak_init();
		mac();
		speak_destroy();
	}

	private void readBits(FileInputStream fis, boolean[] bitsBuffer) {
		int bits = bitsBuffer.length;
		byte buffer[] = new byte[bits / 8];
		int bytesRead = 0;
		try {
			bytesRead = fis.read(buffer);
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (bytesRead == -1)
			return;
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
	}

	private void frames_init(File file) {
		try {

			FileInputStream fis = new FileInputStream(file);
			int length = (int) (file.length() * 8);
			num_frames = length / frame_size;

			for (int j = 0; j < header_size; ++j) {
				for (int k = 0; k < spb; ++k) {
					int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs));
					header[j*spb*2+k*2] = (byte) (waveo & 0xff);
					header[j*spb*2+k*2+1] = (byte) (waveo >> 8);
				}
			}

			boolean[] bitsBuffer = new boolean[frame_size];
			frame_list = new byte[num_frames][];
			for (int i = 0; i < num_frames; ++i) {
				readBits(fis, bitsBuffer);
				byte[] wave = new byte[2*spb*frame_size];
				for (int j = 0; j < frame_size; ++j) {
					for (int k = 0; k < spb; ++k) {
						int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (2 * (bitsBuffer[j] ? 1 : 0) - 1));
						wave[j*spb*2+k*2] = (byte) (waveo & 0xff);
						wave[j*spb*2+k*2+1] = (byte) (waveo >> 8);
					}
				}
				frame_list[i] = wave;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void speak_init() {
		AudioFormat format = getAudioFormat();
		DataLine.Info infoSpeak = new DataLine.Info(SourceDataLine.class, format);
		try {
			speak = (SourceDataLine) AudioSystem.getLine(infoSpeak);
			speak.open(format);
			speak.start();
		} catch (LineUnavailableException e) {
			e.printStackTrace();
		}
	}

	private void speak_destroy() {
		speak.stop();
		speak.close();
	}

	private void rx() {
		while (true) {
		}
	}

	private void send_frame(int frame_no) {
		System.arraycopy(header, 0, to_send, 0, header.length);
		System.arraycopy(frame_list[frame_no], 0, to_send, header.length, frame_list[frame_no].length);
		speak.write(to_send, 0, to_send.length);
	}

	private boolean wait_ack(int frame_no) {
		return false;
	}

	private void link_error() {
		System.out.println("link error");
	}

	private void mac() {
		for (int i = 0; i < num_frames; ++i) {
			send_frame(i);
			boolean ack = wait_ack(i);
			while (!ack) {
				if (retry == max_retry) {
					link_error();
					return;
				}
				System.out.println("Send frame " + i + " retry " + retry);
				++retry;
				send_frame(i);
				ack = wait_ack(i);
			}
			System.out.println("ACK frame " + i);
			retry = 0;
		}
	}

	public static void main(String[] args) {
		try {
			Node node = new Node();
			node.run();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
