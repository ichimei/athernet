package network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class Node {

	final File file = new File("INPUT.bin");
	final byte node_id = 0x00;
	final byte node_tx = (byte) 0xff;
	final byte node_rx = (byte) 0xff;

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

	boolean[] header = new boolean[header_size];
	byte[] intv = new byte[200];
	byte[][] frame_list;
	final int header_len = header_size * spb;
	SourceDataLine speak;
	TargetDataLine mic;

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
		device_init();
		mac();
		device_stop();
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

	private int[] bits_to_ints(boolean[] bits) {
		int[] ints = new int[spb*bits.length];
		for (int j = 0; j < bits.length; ++j) {
			for (int k = 0; k < spb; ++k) {
				ints[j*spb+k] = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
			}
		}
		return ints;
	}

	private void writeBits(ByteArrayOutputStream bos, boolean[] bits) {
		for (int j = 0; j < bits.length; ++j) {
			for (int k = 0; k < spb; ++k) {
				int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
				bos.write((byte) (waveo & 0xff));
				bos.write((byte) (waveo >> 8));
			}
		}
	}

	private boolean[] get_mac_header() {
		return null;
	}

	private void frames_init(File file) {
		try {

			FileInputStream fis = new FileInputStream(file);
			int length = (int) (file.length() * 8);
			num_frames = length / frame_size;

			Arrays.fill(header, true);

			boolean[] bitsBuffer = new boolean[frame_size];
			frame_list = new byte[num_frames][];
			for (int i = 0; i < num_frames; ++i) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				readBits(fis, bitsBuffer);
				writeBits(bos, header);
				//writeBits(bos, get_mac_header());
				writeBits(bos, bitsBuffer);
				frame_list[i] = bos.toByteArray();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void device_init() {
		AudioFormat format = getAudioFormat();
		DataLine.Info infoSpeak = new DataLine.Info(SourceDataLine.class, format);
		DataLine.Info infoMic = new DataLine.Info(TargetDataLine.class, format);
		try {
			speak = (SourceDataLine) AudioSystem.getLine(infoSpeak);
			mic = (TargetDataLine) AudioSystem.getLine(infoMic);
			speak.open(format);
			speak.start();
			mic.open(format);
			mic.start();
		} catch (LineUnavailableException e) {
			e.printStackTrace();
		}
	}

	private void device_stop() {
		speak.stop();
		speak.close();
		mic.stop();
		mic.close();
	}

	private int[] bytes_to_ints(byte[] data) {
		int[] ints = new int[data.length/2];
		for (int i = 0; i < data.length / 2; ++i)
			ints[i] = (data[2*i+1] << 8) | (data[2*i] & 0xff);
		return ints;
	}

	private void packet_detect() {
		byte buffer[] = new byte[header_len * 2];
		int oldBuffer[] = new int[header_len];
		int newBuffer[];
		byte packetBuffer[] = new byte[2000];
		int header_ints[] = bits_to_ints(header);

		int state = 0;

		while (true) {
			if (state == 0) {
				int bytesRead = mic.read(buffer, 0, header_len*2);
				newBuffer = bytes_to_ints(buffer);
				for (int i = 1; i <= header_len; ++i) {
					float syncPower = 0.f;
					for (int j = 0; j < header_len; ++j) {
						syncPower += ((float) header_ints[j] / amp) * ((float) ( j + i < header_len ? oldBuffer[j+1] : newBuffer[j+i-header_len]) / amp);
					}
				}
				oldBuffer = newBuffer;
			} else {
			}
		}
	}

	private void send_frame(int frame_no) {
		byte[] to_send = frame_list[frame_no];
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
			System.out.println("Send frame " + i);
			send_frame(i);
			boolean ack = wait_ack(i);
			while (!ack) {
				if (retry == max_retry) {
					link_error();
					return;
				}
				++retry;
				System.out.println("Send frame " + i + " retry " + retry);
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
