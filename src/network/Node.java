package network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class Node {

	final boolean debug = false;
	ByteArrayOutputStream debug_bos;
	AudioInputStream debug_ais;
	final File debug_in_wav = new File("INPUT.wav");
	final File debug_out_wav = new File("OUTPUT2.wav");

	final static int CRC_POLYNOM = 0x9c;
	final static byte CRC_INITIAL = (byte) 0x00;
	final int[] large_buffer = new int[44100 * 10000];
	int cur = 0;

	final File file_tx = new File("input.bin");
	final static byte node_id = (byte) 0x00;
	final static byte node_tx = (byte) 0xff;
//	final File file_tx = null;
//	final File file_rx = new File("fuck.bin");
//	final byte node_id = (byte) 0x00;
//	final byte node_tx = (byte) 0xff;

	FileOutputStream fos = null;
	File file_rx;

	final long duration = 60000;
	final int frame_size = 400;
	final int amp = 32767;        // amplitude
	final float fs = 44100;       // sample rate
	final float fc = 11025;       // frequency of carrier
	final int spb = 3;            // samples per bit
	final int header_size = 200;
	final int max_retry = 10;
	final float thresPower = 36;
	final float thresPowerCoeff = 33;
	final int thresBack = 1200;
	final long ack_timeout = 1000;
	int retry = 0;
	int send_num_frames = 0;
	int get_num_frames = 0;
	int retry_cnt = 0;
	Integer packet_so_far = 0;

	byte[][] received;
	// byte[][] received1;
	Queue<Integer> ack_to_send = new LinkedList<Integer>();
	Queue<File> files_to_send = new LinkedList<File>();
	boolean[] ack_get;
	boolean[] ack_send;
//	boolean[] safe;
	boolean stopped = false;

	byte[] received_frame;

	byte[] header = new byte[2*header_size];
	int[] header_ints = new int[header_size];
	byte[] intv = new byte[200];
	byte[][] frame_list;
	byte[] dummy_body = new byte[frame_size / 8];
	byte[] ackPayload = new byte[frame_size / 8];
	final int packet_len = (frame_size + 40) * spb;
	SourceDataLine speak;
	TargetDataLine mic;
	Object syncObj = new Object();

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100.f;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = false;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	private void run() {
		try {

			frame_init();
			device_start();
			Thread pd = new Thread(()->{
				try {
					packet_detect();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			});
			Thread mc = new Thread(()->{
				try {
					mac();
				} catch (InterruptedException | IOException e) {
					e.printStackTrace();
				}
			});
			pd.start();
			mc.start();

			Thread.sleep(duration);
			stopped = true;
			pd.join();
			mc.join();

			device_stop();
			System.out.println("Node stopped!");

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void bytes_to_bits(byte[] bytesBuffer, boolean[] bitsBuffer) {
		for (int i = 0; i < bytesBuffer.length; ++i) {
			byte b = bytesBuffer[i];
			for (int j = 0; j < 8; ++j) {
				bitsBuffer[i*8+j] = (b & 1) > 0;
				b >>= 1;
			}
		}
	}

	private static boolean[] bytes_to_bits(byte[] bytesBuffer) {
		boolean[] bitsBuffer = new boolean[bytesBuffer.length * 8];
		for (int i = 0; i < bytesBuffer.length; ++i) {
			byte b = bytesBuffer[i];
			for (int j = 0; j < 8; ++j) {
				bitsBuffer[i*8+j] = (b & 1) > 0;
				b >>= 1;
			}
		}
		return bitsBuffer;
	}

	private static void bits_to_bytes(boolean[] bitsBuffer, byte[] bytesBuffer) {
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

	private static byte bit8_to_byte(boolean[] bit8, int offset) {
		byte ret = 0;
		for (int i = 0; i < 8; ++i) {
			if (bit8[i + offset]) {
				ret |= 1 << i;
			}
		}
		return ret;
	}

	private static boolean[] byte_to_bit8(byte bt) {
		boolean[] bit8 = new boolean[8];
		for (int i = 0; i < 8; ++i) {
			bit8[i] = (bt & 1) > 0;
			bt >>= 1;
		}
		return bit8;
	}

	private static void int32_to_bytes(int it, byte[] bytes, int offset) {
		for (int i = 0; i < 4; ++i) {
			bytes[i + offset] = (byte) (it & 0xff);
			it >>= 8;
		}
	}

	private static int bytes_to_int32(byte[] bytes, int offset) {
		int it = 0;
		for (int i = 0; i < 4; ++i) {
			it |= bytes[i] << (8 * i);
		}
		return it;
	}

	private static void str_to_bytes(String str, byte[] bytes, int offset) {
		byte[] str_bytes = str.getBytes();
		int str_len = str.length();
		System.arraycopy(str_bytes, 0, bytes, offset, str_len);
		bytes[offset + str_len] = 0;
	}

	private static String bytes_to_str(byte[] bytes, int offset) {
		String s = new String();
		for (int i = 0; i < 21; ++i) {
			byte b = bytes[offset + i];
			if (b != 0) {
				s += (char) b;
			} else {
				break;
			}
		}
		return s;
	}

//	private int[] bits_to_ints(boolean[] bits) {
//		int[] ints = new int[spb*bits.length];
//		for (int j = 0; j < bits.length; ++j) {
//			for (int k = 0; k < spb; ++k) {
//				ints[j*spb+k] = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
//			}
//		}
//		return ints;
//	}

	private void write_bits_analog(ByteArrayOutputStream bos, boolean[] bits) {
		for (int j = 0; j < bits.length; ++j) {
			for (int k = 0; k < spb; ++k) {
				int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
				bos.write((byte) (waveo & 0xff));
				bos.write((byte) (waveo >> 8));
			}
		}
	}

	private void write_bytes_analog(ByteArrayOutputStream bos, byte[] bytes) {
		write_bits_analog(bos, bytes_to_bits(bytes));
	}

	private void write_byte_analog(ByteArrayOutputStream bos, byte bt) {
		write_bits_analog(bos, byte_to_bit8(bt));
	}

	private byte[] get_mac_header(byte dest, byte src, boolean ack, int frame_no) {
		byte[] mac_header = {dest, src, (byte) (ack ? 0xff : 0), (byte) frame_no};
		return mac_header;
	}

	private byte get_crc(byte[] data, int offset, int len) {
		CRC8 crc8 = new CRC8(CRC_POLYNOM, CRC_INITIAL);
		crc8.update(data, offset, len);
		byte crc = (byte) crc8.getValue();
		return crc;
	}

	private byte get_crc(byte[] data) {
		return get_crc(data, 0, data.length);
	}

	private void frame_init() {
		float fp[] = new float[header_size];
		float halfLenHeader = header_size / 2;
		for (int i = 0; i < header_size / 2; ++i)
			fp[i] = 2000.f + i / halfLenHeader * 8000.f;
		for (int i = header_size / 2; i < header_size; ++i)
			fp[i] = 10000.f - (i - header_size / 2) / halfLenHeader * 8000.f;

		float cum = 0.f;
		for (int i = 0; i < header_size; ++i) {
			cum += fp[i];
			int wave = (int) (amp * Math.sin(2 * Math.PI * cum / fs));
			header_ints[i] = wave;
			header[2*i] = (byte) (wave & 0xff);
			header[2*i+1] = (byte) (wave >> 8);
		}
	}

	private void send_file_init(File file) throws IOException {
		FileInputStream fis = new FileInputStream(file);
		System.out.println("Start to send: " + file.getName());
		int length = (int) file.length();
		send_num_frames = length * 8 / frame_size;
		if ((length * 8) % frame_size > 0)
			send_num_frames++;
		System.out.println("File length: " + length);
		System.out.println("Num frames: " + send_num_frames);
		ack_get = new boolean[send_num_frames + 1];
		byte[] macPayload = new byte[frame_size / 8];
		frame_list = new byte[send_num_frames + 1][];

		for (int i = 0; i <= send_num_frames; ++i) {
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(node_tx, node_id, false, i));
			if (i == 0) {
				int32_to_bytes((int) file.length(), macPayload, 0);
				str_to_bytes(file.getName(), macPayload, 4);
			} else {
				fis.read(macPayload);
			}
			phyPayloadStream.write(macPayload);
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			frame_list[i] = bos.toByteArray();
		}

		fis.close();
	}

	private void send_file_meta() throws InterruptedException {
		boolean ack_got = false;
		while (!ack_got) {
			System.out.println("Sending...");
			send_frame(0);
			synchronized (syncObj) {
				syncObj.wait(2000);
			}
			synchronized (ack_get) {
				ack_got = ack_get[0];
			}
		}
	}

	private void device_start() throws LineUnavailableException {
		AudioFormat format = getAudioFormat();
		speak = AudioSystem.getSourceDataLine(format);
		mic = AudioSystem.getTargetDataLine(format);
		speak.open();
		speak.start();
		mic.open();
		mic.start();
	}

	private void device_stop() {
		speak.stop();
		speak.close();
		mic.stop();
		mic.close();
	}

	private void bytes_to_ints(byte[] data, int[] ints, int offset, int len) {
		for (int i = 0; i < len; ++i)
			ints[i + offset] = (data[2*i+1] << 8) | (data[2*i] & 0xff);
	}

	private void packet_detect() throws FileNotFoundException {
		int buffer_len = 1000;
		byte buffer[] = new byte[buffer_len * 2];

		boolean syncState = true;
		float maxSyncPower = 0.f;
		int start = 0;
		float power = 0.f;
		cur = buffer_len;
		while (!stopped && cur < large_buffer.length) {
			if (cur % buffer_len == 0) {
				int bytesRead = mic.read(buffer, 0, buffer_len*2);
				if (bytesRead < buffer_len*2)
					return;
				bytes_to_ints(buffer, large_buffer, cur, buffer_len);
			}
			power = power * 63.f / 64.f + ((float) large_buffer[cur] / amp)
					* ((float) large_buffer[cur] / amp) / 64.f;

			if (syncState) {
				float syncPower = 0.f;
				for (int j = 0; j < header_size; ++j) {
					syncPower += ((float) header_ints[j] / amp) *
							((float) large_buffer[cur+j-header_size+1] / amp);
				}
				if (syncPower > power * thresPowerCoeff &&
						syncPower > maxSyncPower &&
						syncPower > thresPower) {
					maxSyncPower = syncPower;
					start = cur;
				} else if (cur - start > thresBack && start != 0) {
					maxSyncPower = 0.f;
					syncState = false;
				}
			} else {
				if (cur - start == packet_len) {
//					System.out.println("Start is: " + System.currentTimeMillis());
					boolean decoded[] = new boolean[packet_len/spb];
					for (int j = 0; j < packet_len / spb; ++j) {
						float sumRmCarr = 0.f;
						for (int k = 0; k < spb; ++k)
							sumRmCarr += (large_buffer[start+1+j*spb+k])
							* (float) Math.sin(2 * Math.PI * fc * k / fs);
						decoded[j] = sumRmCarr > 0.f;
					}
					packet_ana(decoded);
					syncState = true;
					start = 0;
				}
			}
			++cur;
		}
		System.out.println("Packet detector stopped!");
		synchronized (ack_send) {
			int count = 0;
			for (int frame_no = 0; frame_no < get_num_frames; ++frame_no) {
				if (ack_send[frame_no])
					count++;
			}
			System.out.println(count);
		}
//		synchronized (received) {
//			for (int frame_no = 0; frame_no < num_frames; frame_no++) {
//				System.out.println(frame_no);
//				if (received[frame_no] != null) {
//					try {
//						fos.write(received[frame_no], 4, frame_size / 8);
//					} catch (IOException e) {
//						e.printStackTrace();
//					}
//				} else {
////					System.out.println(received1.length);
//					try {
//						fos.write(dummy_body, 0, frame_size / 8);
//					} catch (IOException e) {
//						e.printStackTrace();
//					}
//				}
//			}
//		}
	}

	private void packet_ana(boolean[] decoded) throws FileNotFoundException {
//		System.out.println("There is a packet");
		byte packet_dest = bit8_to_byte(decoded, 0);
		if (packet_dest != node_id) {
			System.out.println("To: " + bit8_to_byte(decoded, 0));
			return;
		}
		byte packet_src = bit8_to_byte(decoded, 8);
		byte type = bit8_to_byte(decoded, 16);
		int frame_no = bit8_to_byte(decoded, 24) & 0xff;
		if (type == (byte) 0xff) {
			// ACK type
			byte decoded_bytes[] = new byte[decoded.length / 8];
			bits_to_bytes(decoded, decoded_bytes);
			if (get_crc(decoded_bytes, 0, frame_size / 8 + 4)
					!= decoded_bytes[frame_size / 8 + 4]) {
				System.out.println("Wrong ACK");
				return;
			} else {
				if (packet_src == node_tx) {
					System.out.println("ACK: " + frame_no);
					if (frame_no >= 0 && frame_no <= ack_get.length) {
						synchronized (ack_get) {
							boolean new_ack = !ack_get[frame_no];
							if (new_ack) {
								ack_get[frame_no] = true;
								if (frame_no == 0) {
									synchronized (syncObj) {
										syncObj.notify();
									}
								} else {
									synchronized (packet_so_far) {
										packet_so_far++;
									}
								}
							}
						}
					} else {
						System.out.println("Wrong ACK");
					}
				} else {
					System.out.println("Who is that?");
				}
				return;
			}
		} else {
			if (packet_src != node_tx) {
				System.out.println("Who is that?");
				return;
			}
			// normal packet!
			byte decoded_bytes[] = new byte[decoded.length / 8];
			bits_to_bytes(decoded, decoded_bytes);
			if (get_crc(decoded_bytes, 0, frame_size / 8 + 4)
					!= decoded_bytes[frame_size / 8 + 4]) {
				// crc failed! pretend not detected
				System.out.println("CRC failed!");
				return;
			} else {
				// this is correct packet!
				System.out.println("Correct packet: " + frame_no);
				if (frame_no == 0) {
					int length = bytes_to_int32(decoded_bytes, 0);
					String filename = bytes_to_str(decoded_bytes, 4);
					file_rx = new File(filename);
					get_num_frames = (length * 8) / frame_size;
					if ((length * 8) % frame_size > 0)
						get_num_frames++;
					received = new byte[get_num_frames][];
					ack_send = new boolean[get_num_frames];
					ack_send[0] = true;
				} else {
					synchronized (ack_send) {
						boolean ack_sent = ack_send[frame_no];
						if (!ack_sent) {
							ack_send[frame_no] = true;
							received[frame_no] = decoded_bytes;
						}
					}
				}
				// send ack!
				ack_to_send.offer(frame_no);
			}
		}
	}

	// send ack
	private void send_ack(int frame_no) throws IOException {
		ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		phyPayloadStream.write(get_mac_header(node_tx, node_id, true, frame_no));
		phyPayloadStream.write(ackPayload);
		byte[] phyPayload = phyPayloadStream.toByteArray();
		bos.write(header);
		write_bytes_analog(bos, phyPayload);
		write_byte_analog(bos, get_crc(phyPayload));
		byte[] to_send = bos.toByteArray();
		speak.write(to_send, 0, to_send.length);
	}

	// send a frame
	private void send_frame(int frame_no) {
		byte[] to_send = frame_list[frame_no];
		speak.write(to_send, 0, to_send.length);
	}

	private void mac() throws InterruptedException, IOException {
		while (!stopped) {
			if (files_to_send.isEmpty()) {
				break;
			}
			File file = files_to_send.poll();
			send_file_init(file);
			send_file_meta();
			synchronized (packet_so_far) {
				packet_so_far = 0;
			}

			while (!stopped) {
				synchronized (packet_so_far) {
					if (packet_so_far == send_num_frames) {
						break;
					}
				}
				int wait_count = 0;
				for (int i = 0; i < get_num_frames; ++i) {
					synchronized (ack_to_send) {
						if (!ack_to_send.isEmpty()) {
							send_ack(ack_to_send.poll());
						}
					}
					synchronized (ack_get) {
						if (ack_get[i]) {
							continue;
						}
					}
					System.out.println("Send frame " + i);
					if (wait_count == 40) {
						wait_count = 0;
						System.out.println("Wait! " + i);
						Thread.sleep(1000);
					}
					send_frame(i);
					wait_count++;
				}
			}
		}
		System.out.println("Transmission complete! Mac stopped!");

		// flush ack
		while (!stopped) {
			synchronized(ack_to_send) {
				if (!ack_to_send.isEmpty()) {
					send_ack(ack_to_send.poll());
				}
			}
		}
	}

	public static void main(String[] args) {
		final File file_1 = new File("input.bin");
		Node node = new Node();
		node.files_to_send.offer(file_1);
		node.run();
	}

}
