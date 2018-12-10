package network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.stream.IntStream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class Node {

	final boolean debug = false;
	ByteArrayOutputStream debug_bos;
	AudioInputStream debug_ais;
	final File debug_in_wav = new File("INPUT.wav");
	final File debug_out_wav = new File("OUTPUT2.wav");

	final int CRC_POLYNOM = 0x9c;
	final byte CRC_INITIAL = (byte) 0x00;
	final int[] large_buffer = new int[44100 * 10000];
	int cur = 0;

	final File file_tx = new File("input.bin");
	final File file_rx = new File("output.bin");
	final byte node_id = (byte) 0xff;
	final byte node_tx = (byte) 0x00;
//	final File file_tx = null;
//	final File file_rx = new File("fuck.bin");
//	final byte node_id = (byte) 0x00;
//	final byte node_tx = (byte) 0xff;

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
	int num_frames = 0;
	int retry_cnt = 0;
	int packet_so_far = 0;

	byte[][] received;
	// byte[][] received1;
	Queue<Integer> ack_to_send;
	boolean[] ack_get;
	int[] count;
	boolean[] ack_send;
	boolean[] safe;
	long[] start_time;
	boolean stopped = false;

	byte[] received_frame;

	byte[] header = new byte[2*header_size];
	int[] header_ints = new int[header_size];
	byte[] intv = new byte[200];
	byte[][] frame_list;
	byte[] dummy_body = new byte[frame_size / 8];
	boolean[] ack_body;
	boolean[] ack_crc;
	final int packet_len = (frame_size + 40) * spb;
	SourceDataLine speak;
	TargetDataLine mic;
	FileOutputStream fos;
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
		frame_init(file_tx);
		device_start();
		Thread pd = new Thread(()->packet_detect());
		Thread mc = new Thread(()->mac());
		pd.start();
		mc.start();
		try {
			Thread.sleep(duration);
			stopped = true;
			pd.join();
			mc.join();
		} catch (Exception e) {
			e.printStackTrace();
		}
		device_stop();
		System.out.println("Node stopped!");
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

	private byte bit8_to_byte(boolean[] bit8, int offset) {
		byte ret = 0;
		for (int i = 0; i < 8; ++i) {
			if (bit8[i + offset]) {
				ret |= 1 << i;
			}
		}
		return ret;
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

	private void writeBits(ByteArrayOutputStream bos, boolean[] bits) {
		for (int j = 0; j < bits.length; ++j) {
			for (int k = 0; k < spb; ++k) {
				int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
				bos.write((byte) (waveo & 0xff));
				bos.write((byte) (waveo >> 8));
			}
		}
	}

	private boolean[] get_mac_header(byte dest, byte src, boolean ack, int frame_no) {
		byte[] mac_header = {dest, src, (byte) (ack ? 0xff : 0), (byte) frame_no};
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

	private void frame_init(File file) {
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
		if (file == null) {
			return;
		}

		try {
			FileInputStream fis = new FileInputStream(file);
			int length = (int) (file.length() * 8);
			num_frames = length / frame_size;
			System.out.println(num_frames);
			ack_get = new boolean[num_frames];
			ack_send = new boolean[num_frames];
			start_time = new long[num_frames];
			count = new int[1];
			count[0] = 0;
			safe = new boolean[1];
			safe[0] = false;
			ack_to_send = new LinkedList<Integer>();
			received = new byte[num_frames][];
			byte[] bytesBuffer = new byte[frame_size / 8];
			boolean[] bitsBuffer = new boolean[frame_size];
			frame_list = new byte[num_frames][];
			for (int i = 0; i < num_frames; ++i) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				fis.read(bytesBuffer);
				bytes_to_bits(bytesBuffer, bitsBuffer);
				bos.write(header);
				writeBits(bos, get_mac_header(node_tx, node_id, false, i));
//				System.out.println(bos.toByteArray().length);
				writeBits(bos, bitsBuffer);
				writeBits(bos, get_crc(bytesBuffer, 0, bytesBuffer.length));
				frame_list[i] = bos.toByteArray();
				if (i == 0) {
					ack_body = bitsBuffer;
					ack_crc = get_crc(bytesBuffer, 0, bytesBuffer.length);
				}
//				System.out.println(bos.toByteArray().length);
			}
			fis.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void device_start() {
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
		} catch (Exception e) {
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

	private void bytes_to_ints(byte[] data, int[] ints, int offset, int len) {
		for (int i = 0; i < len; ++i)
			ints[i + offset] = (data[2*i+1] << 8) | (data[2*i] & 0xff);
	}

	private void bytes_to_ints1(byte[] data, int[] ints, int len) {
		for (int i = 0; i < len; ++i)
			ints[i] = Math.abs((data[2*i+1] << 8) | (data[2*i] & 0xff));
	}

	private void packet_detect() {
		int buffer_len = 1000;
		byte buffer[] = new byte[buffer_len * 2];

		boolean syncState = true;
		float maxSyncPower = 0.f;
		int start = 0;
		float power = 0.f;
		cur = buffer_len;
//		int next_read = cur;
		while (!stopped && cur < large_buffer.length) {
			if (cur % buffer_len == 0) {
				int bytesRead = 0;
				int[] sound;
				sound = new int[44100];
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
//				next_read = cur + bytesRead / 2;
				bytes_to_ints(buffer, large_buffer, cur, buffer_len);
				bytes_to_ints1(buffer, sound, buffer_len);
				int sum = IntStream.of(sound).sum();
//				System.out.println(sum);
				synchronized (safe) {
					if (sum < 2000000) {
//						System.out.println("__________________________");
						safe[0] = true;
						synchronized(syncObj) {
							syncObj.notify();
						}
					} else {
						safe[0] = false;
					}
				}
			}
			power = power * 63.f / 64.f + ((float) large_buffer[cur] / amp) * ((float) large_buffer[cur] / amp) / 64.f;

			if (syncState) {
				float syncPower = 0.f;
				for (int j = 0; j < header_size; ++j) {
					syncPower += ((float) header_ints[j] / amp) * ((float) large_buffer[cur+j-header_size+1] / amp);
				}
				if (syncPower > power * thresPowerCoeff && syncPower > maxSyncPower && syncPower > thresPower) {
					maxSyncPower = syncPower;
					start = cur;
//					synchronized(safe) {
//						//System.out.println("+++++++++++++++++++++++++++++++");
//						safe[0] = false;
//					}
				} else if (cur - start > thresBack && start != 0) {
					maxSyncPower = 0.f;
					syncState = false;
//					synchronized(safe) {
//						//System.out.println("__________________________");
//						safe[0] = true;
//						synchronized(syncObj) {
//							syncObj.notify();
//						}
//					}
				}
			} else {
//				synchronized(safe) {
//					//System.out.println("__________________________");
//					safe[0] = true;
//					synchronized(syncObj) {
//						syncObj.notify();
//					}
//				}
				if (cur - start == packet_len) {
//					System.out.println("Start is: " + System.currentTimeMillis());
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
		System.out.println("Packet detector stopped!");
		synchronized (ack_send) {
			int count = 0;
			for (int frame_no = 0; frame_no < num_frames; ++frame_no) {
				if (ack_send[frame_no])
					count++;
			}
			System.out.println(count);
		}
		synchronized (safe) {
			safe[0] = true;
		}
		synchronized (received) {
			for (int frame_no = 0; frame_no < num_frames; frame_no++) {
				System.out.println(frame_no);
				if (received[frame_no] != null) {
					try {
						fos.write(received[frame_no], 4, frame_size / 8);
					} catch (IOException e) {
						e.printStackTrace();
					}
				} else {
//					System.out.println(received1.length);
					try {
						fos.write(dummy_body, 0, frame_size / 8);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	private void packet_ana(boolean[] decoded) {
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
			byte decoded_bytes[] = new byte[decoded.length / 8];
			bits_to_bytes(decoded, decoded_bytes);
			if (get_crc_byte(decoded_bytes, 4, frame_size / 8) != decoded_bytes[frame_size / 8 + 4]) {
				System.out.println("Wrong ACK");
				return;
			} else {
				if (packet_src == node_tx) {
					synchronized (safe) {
						safe[0] = true;
						synchronized (syncObj) {
							syncObj.notify();
						}
					}
	//				synchronized(syncObj) {
	//					syncObj.notify();
	//				}
					System.out.println("ACK: " + frame_no);
					if (frame_no >= 0 && frame_no < ack_get.length) {
//						ack_get[frame_no] = true;
						boolean new_ack = false;
						synchronized (ack_get) {
							new_ack = !ack_get[frame_no];
							ack_get[frame_no] = true;
							if (new_ack) {
								synchronized (count) {
									count[0]++;
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
			if (get_crc_byte(decoded_bytes, 4, frame_size / 8) != decoded_bytes[frame_size / 8 + 4]) {
				// crc failed! pretend not detected
				System.out.println("CRC failed!");
				return;
			} else if (file_rx != null) {
				// this is correct packet!
				synchronized (safe) {
					safe[0] = true;
					synchronized(syncObj) {
						syncObj.notify();
					}
				}
				System.out.println("Correct packet: " + frame_no);
				synchronized(ack_send) {
					if (frame_no < ack_send.length) {
						ack_send[frame_no] = true;
					}
				}
				synchronized(received) {
					if (frame_no < received.length) {
						received[frame_no] = decoded_bytes;
					}
				}
				// send ack!
				ack_to_send.offer(frame_no);
			}
		}
	}

	// send ack
	private void send_ack(int frame_no) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			bos.write(header);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		writeBits(bos, get_mac_header(node_tx, node_id, true, frame_no));
		synchronized (ack_body) {
			writeBits(bos, ack_body);
		}
		synchronized (ack_crc) {
			writeBits(bos, ack_crc);
		}
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

	// send a frame
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
			start_time[frame_no] = System.currentTimeMillis();
		}
	}

	// wait for specified seconds
	private void wait_ack() {
		synchronized (syncObj) {
			//long start = System.currentTimeMillis();
        	try {
        		syncObj.wait(ack_timeout);
        	}catch(InterruptedException e) {
        		e.printStackTrace();
        	}
        }
	}

	// wait for 1 second
	private void wait_ack1() {
		synchronized (syncObj) {
			try {
				syncObj.wait(1000);
			}catch(Exception e) {
				e.printStackTrace();
			}
		}
	}

	@SuppressWarnings("unused")
	private void link_error() {
		System.out.println("link error");
	}

	// used in macperf
	private void send_packet() {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			bos.write(header);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		writeBits(bos, get_mac_header(node_tx, node_id, false, 0));
		writeBits(bos, new boolean[1]);
		byte[] to_send = bos.toByteArray();
		speak.write(to_send, 0, to_send.length);
	}

	@SuppressWarnings("unused")
	private void macperf() {
		System.out.println("fuck you");
		long start = System.currentTimeMillis();
		int count = 0;
		while(true) {
			System.out.println(System.currentTimeMillis()-start);
			if (System.currentTimeMillis() - start >= 10000) {
				System.out.println(count);
				start = System.currentTimeMillis();
				break;
			} else {
				send_packet();
				long start1 = System.currentTimeMillis();
				wait_ack1();
				if(System.currentTimeMillis() - start1 <= 1000)
					count += 600;
			}
		}
		System.out.println("qqqq"+count/10);
		System.out.println("dsdsds");
	}

	private void send_ack_raw() {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			bos.write(header);
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		writeBits(bos, get_mac_header(node_tx, node_id, false, 0));
		byte[] to_send = bos.toByteArray();
		speak.write(to_send, 0, to_send.length);
	}

	private void wait_ack2() {
		synchronized(syncObj) {
			try {
				syncObj.wait(2000);
			}catch(Exception e){
				e.printStackTrace();
			}
		}
	}

	@SuppressWarnings("unused")
	private void macping() {
		int count = 0;
		int sum = 0;
		while(count < 10) {
			long start = System.currentTimeMillis();
			send_ack_raw();
			wait_ack2();
			long end = System.currentTimeMillis();
			if(end-start>=2000) {
				System.out.println("Time out");
			}else {
				count += 1;
				System.out.println(end - start);
				sum += end - start;
			}
		}
		System.out.println("dsdsds" + sum/10);
	}

	private void mac() {
		int wait_count = 0;
//		send_ack(5);
		long start_send = System.currentTimeMillis();
		while (!stopped) {
			synchronized (count) {
				if (count[0] == num_frames) {
					break;
				}
			}
			for (int i = 0; i < num_frames; ++i) {
				synchronized (ack_to_send) {
					if (!ack_to_send.isEmpty()) {
						boolean is_safe;
						synchronized (safe) {
							is_safe = safe[0];
						}
						if (!is_safe) {
							System.out.println("Back off!!");
							wait_ack();
						}
						send_ack(ack_to_send.poll());
					}
				}
				synchronized (ack_get) {
					if (ack_get[i]) {
						continue;
					}
				}
				boolean is_safe;
				synchronized(safe) {
					is_safe = safe[0];
				}
				if (!is_safe) {
					System.out.println("Back off");
					wait_ack();
				}
				System.out.println("Send frame " + i);
				if (wait_count == 40) {
					wait_count = 0;
					System.out.println("Wait! " + i);
					try {
						Thread.sleep(1000);
					} catch(Exception e) {
						e.printStackTrace();
					}
				}
				send_frame(i);
				wait_count++;
//				boolean ack = wait_ack(i);
//				while (!ack) {
//					if (retry == max_retry) {
//						link_error();
//						stopped = true;
//						return;
//					}
//					++retry;
//					System.out.println("Send frame " + i + " retry " + retry +" "+ System.currentTimeMillis());
//					send_frame(i);
//					ack = wait_ack(i);
//				}
//				System.out.println("ACK frame " + i);
				retry = 0;
			}
		}
		System.out.println("Transmission complete! Mac stopped!");

		long start_end = System.currentTimeMillis();
		long time = start_end - start_send;

		// for (int x = 0; x < 20; ++x) {
		// 	System.out.println("Transmission complete! Mac stopped!" + time);
		// }

		// flush ack
		while (!stopped) {
			synchronized(ack_to_send) {
				if (!ack_to_send.isEmpty()) {
					boolean is_safe;
					synchronized (safe) {
						is_safe = safe[0];
					}
					System.out.println("Back off!!");
					if (!is_safe) {
						wait_ack();
					}
					send_ack(ack_to_send.poll());
				}
			}
		}

		if (debug) {
			AudioFormat format = getAudioFormat();
			byte[] data = debug_bos.toByteArray();
			try {
				AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, data.length), AudioFileFormat.Type.WAVE, debug_out_wav);
			} catch (Exception e) {
				e.printStackTrace();
			}
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
