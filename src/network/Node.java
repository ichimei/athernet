package network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class Node {
	int x = 0;
	final boolean debug = true;
	ByteArrayOutputStream debug_bos;
	AudioInputStream debug_ais;
	final File debug_in_wav = new File("INPUT.wav");
	final File debug_out_wav = new File("OUTPUT2.wav");

    final int CRC_POLYNOM = 0x9c;
    final byte CRC_INITIAL = (byte) 0x00;
    final int[] large_buffer = new int[44100 * 20];
    int cur = 0;

//	final File file_tx = new File("INPUT.bin");
//	final File file_rx = null;
    final File file_tx = null;
	final File file_rx = new File("OUTPUT.bin");
	final byte node_id = (byte) 0xff;
	final byte node_tx = (byte) 0x00;
	final byte node_rx = (byte) 0x00;

	final int frame_size = 200;
	final int amp = 32767;        // amplitude
	final float fs = 44100;       // sample rate
	final float fc = 11025;       // frequency of carrier
	final int spb = 6;            // samples per bit
	final int header_size = 20;
	final int max_retry = 5;
	final float thresPower = 30;
	final float thresPowerCoeff = 100;
	final int thresBack = 100;
	final long ack_timeout = 200;
	int retry = 0;
	int num_frames = 0;
	int retry_cnt = 0;

	boolean get_ack = false;
	boolean stopped = false;

	byte[] received_frame;

	boolean[] header = new boolean[header_size];
	int[] header_ints;
	byte[] intv = new byte[200];
	byte[][] frame_list;
	final int header_len = header_size * spb;
	final int packet_len = (frame_size + 32) * spb;
	SourceDataLine speak;
	TargetDataLine mic;
	FileOutputStream fos;

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
		frames_init(file_tx);
		device_init();
		new Thread(()->packet_detect()).start();
		mac();
		device_stop();
	}

	private void bytes_to_bits(byte[] bytesBuffer, boolean[] bitsBuffer) {
		for (int i = 0; i < bytesBuffer.length; ++i) {
			byte b = bytesBuffer[i];
			for (int j = 0; j < 8; ++j) {
				bitsBuffer[i*8+j] = (b & 1) > 0;
				b >>= 1;
			}
		}
	}

	private void bits_to_bytes(boolean[] bitsBuffer, byte[] bytesBuffer) {
		for (int i = 0; i < bytesBuffer.length; ++i) {
			byte b = 0;
			for (int j = 0; j < 8; ++j) {
				if (bitsBuffer[i*8+j]) {
					b |= 1 << j;
				}
			}
			bytesBuffer[i] = b;
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

	private boolean[] get_mac_header(byte dest, byte src, boolean ack) {
		byte[] mac_header = {dest, src, (byte) (ack ? 0xff : 0)};
		boolean[] mac_header_bits = new boolean[mac_header.length * 8];
		bytes_to_bits(mac_header, mac_header_bits);
		return mac_header_bits;
	}

	private byte get_crc_byte(byte[] data, int offset, int len) {
		CRC8 crc8 = new CRC8(CRC_POLYNOM, CRC_INITIAL);
		crc8.update(data, offset, len);
		byte crc = (byte) crc8.getValue();
		return crc;
	}

	private boolean[] get_crc(byte[] data, int offset, int len) {
		CRC8 crc8 = new CRC8(CRC_POLYNOM, CRC_INITIAL);
		crc8.update(data, offset, len);
		byte crc = (byte) crc8.getValue();
		boolean[] result = new boolean[8];
		for (int i = 0; i < 8; ++i) {
			result[i] = (crc & 1) > 0;
			crc >>= 1;
		}
		return result;
	}

	private void frames_init(File file) {
		try {

			for (int i = 0; i < header.length; ++i) {
				header[i] = (i % 2 == 0);
			}
			header_ints = bits_to_ints(header);

			if (file == null)
				return;

			FileInputStream fis = new FileInputStream(file);
			int length = (int) (file.length() * 8);
			num_frames = length / frame_size;

			byte[] bytesBuffer = new byte[frame_size / 8];
			boolean[] bitsBuffer = new boolean[frame_size];
			frame_list = new byte[num_frames][];
			for (int i = 0; i < num_frames; ++i) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				fis.read(bytesBuffer);
				bytes_to_bits(bytesBuffer, bitsBuffer);
				writeBits(bos, header);
				writeBits(bos, get_mac_header(node_tx, node_id, false));
				writeBits(bos, bitsBuffer);
				writeBits(bos, get_crc(bytesBuffer, 0, bytesBuffer.length));
				frame_list[i] = bos.toByteArray();
			}
			fis.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void device_init() {
		if (debug) {
			debug_bos = new ByteArrayOutputStream();
			try {
				debug_ais = AudioSystem.getAudioInputStream(debug_in_wav);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (file_rx != null) {
			try {
				fos = new FileOutputStream(file_rx);
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		}
		AudioFormat format = getAudioFormat();
		try {
			speak = AudioSystem.getSourceDataLine(format);
			mic = AudioSystem.getTargetDataLine(format);
			speak.open();
			speak.start();
			mic.open();
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
		if (file_rx != null) {
			try {
				fos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void bytes_to_ints(byte[] data, int[] ints, int offset) {
		for (int i = 0; i < data.length / 2; ++i)
			ints[i + offset] = (data[2*i+1] << 8) | (data[2*i] & 0xff);
	}

	private void packet_detect() {
		int buffer_len = 200;
		byte buffer[] = new byte[buffer_len * 2];

		boolean syncState = true;
		float maxSyncPower = 0.f;
		int start = 0;
		float power = 0.f;
		cur = buffer_len;

		while (!stopped && cur < large_buffer.length) {
			if (cur % buffer_len == 0) {
				int bytesRead = 0;
				if (debug) {
					try {
						bytesRead = debug_ais.read(buffer, 0, buffer_len*2);
					} catch (IOException e) {
						e.printStackTrace();
					}
				} else {
					bytesRead = mic.read(buffer, 0, buffer_len*2);
				}
				if (bytesRead < buffer_len*2)
					return;
				bytes_to_ints(buffer, large_buffer, cur);
			}
			if (syncState) {
				power = power * 63.f / 64.f + ((float) large_buffer[cur] / amp) * ((float) large_buffer[cur] / amp) / 64.f;
				float syncPower = 0.f;
				for (int j = 0; j < header_len; ++j) {
					syncPower += ((float) header_ints[j] / amp) * ((float) large_buffer[cur+j-header_len+1] / amp);
				}
				if (syncPower > power * thresPowerCoeff && syncPower > maxSyncPower && syncPower > thresPower) {
					maxSyncPower = syncPower;
					start = cur;
				} else if (cur - start > thresBack && start != 0) {
					System.out.println("Start is: " + start);
					power = 0.f;
					maxSyncPower = 0.f;
					syncState = false;
				}
			} else {
				if (cur - start == packet_len) {
					System.out.println("Packet detected: " + x);
					++x;
					boolean decoded[] = new boolean[packet_len/spb];
					for (int j = 0; j < packet_len / spb; ++j) {
						float sumRmCarr = 0.f;
						for (int k = 0; k < spb; ++k)
							sumRmCarr += (large_buffer[start+1+j*spb+k]) * (float) Math.sin(2 * Math.PI * fc * k / fs);
						decoded[j] = sumRmCarr > 0.f;
					}
					packet_ana(decoded);
					syncState = true;
					start = 0;
				}
			}
			++cur;
		}
	}

	private byte bit8_to_byte(boolean[] bit8, int offset) {
		byte ret = 0;
		for (int i = 0; i < 8; ++i) {
			if (bit8[i]) {
				ret |= 1 << i;
			}
		}
		return ret;
	}

	private void packet_ana(boolean[] decoded) {
		if (bit8_to_byte(decoded, 0) != node_id) {
			System.out.println("Not me, to: " + bit8_to_byte(decoded, 0));
			return;
		}
		System.out.println("To me!");
		byte packet_src = bit8_to_byte(decoded, 8);
		byte type = bit8_to_byte(decoded, 16);
		if (type > 0x7f) {
			System.out.println("ACK!");
			// ACK!
			get_ack = true;
			return;
		} else {
			System.out.println("Normal!");
			// normal packet!
			byte decoded_bytes[] = new byte[decoded.length / 8];
			bits_to_bytes(decoded, decoded_bytes);
			if (get_crc_byte(decoded_bytes, 3, frame_size / 8) != decoded_bytes[frame_size / 8 + 3]) {
				// crc failed! pretend not detected
				System.out.println("CRC failed!");
				return;
			} else if (file_rx != null) {
				System.out.println("My packet!");
				// this is my packet! write it
				try {
					fos.write(decoded_bytes, 3, frame_size / 8);
				} catch (IOException e) {
					e.printStackTrace();
				}
				// send ack!
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				writeBits(bos, get_mac_header(node_rx, node_id, true));
				writeBits(bos, new boolean[frame_size + 8]);
				byte[] to_send = bos.toByteArray();
				if (debug) {
					try {
						debug_bos.write(to_send);
					} catch (IOException e) {
						e.printStackTrace();
					}
				} else {
					speak.write(to_send, 0, to_send.length);
				}
			}
		}
	}

	private void send_frame(int frame_no) {
		byte[] to_send = frame_list[frame_no];
		if (debug) {
			try {
				debug_bos.write(to_send);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} else {
			speak.write(to_send, 0, to_send.length);
		}
	}

	private boolean wait_ack() {
		if (debug)
			return true;
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() - start < ack_timeout) {
			if (get_ack) {
				get_ack = false;
				return true;
			}
		}
		return false;
	}

	private void link_error() {
		System.out.println("link error");
	}

	private void mac() {
		for (int i = 0; i < num_frames; ++i) {
			System.out.println("Send frame " + i);
			send_frame(i);
			boolean ack = wait_ack();
			while (!ack) {
				if (retry == max_retry) {
					link_error();
					stopped = true;
					return;
				}
				++retry;
//				System.out.println("Send frame " + i + " retry " + retry);
				send_frame(i);
				ack = wait_ack();
			}
//			System.out.println("ACK frame " + i);
			retry = 0;
		}
		System.out.println("Transmission complete!");
		if (debug) {
			AudioFormat format = getAudioFormat();
			byte[] data = debug_bos.toByteArray();
			try {
				AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, data.length), AudioFileFormat.Type.WAVE, debug_out_wav);
				Thread.sleep(5000);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		stopped = true;
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
