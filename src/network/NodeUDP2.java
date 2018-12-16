package network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class NodeUDP2 {

	final static byte TYPE_ACK = (byte) 0xff;
	final static byte TYPE_UDP_SEND = (byte) 0x0f;
	final static byte TYPE_UDP_RECV = (byte) 0xf0;
	final static int CRC_POLYNOM = 0x9c;
	final static byte CRC_INITIAL = (byte) 0x00;

	final static byte NODE_ID = (byte) 0xff;
	final static byte NODE_TX = (byte) 0x00;

	final int[] large_buffer = new int[44100 * 600];
	int cur = 0;

	File file_rx = null;
	final static File notify_file = new File("udp.notify");
	final static File notify_rfile = new File("udpr.notify");

	final static long duration = 600000;
	final static int frame_size = 512;   // must be 8x
	final static int amp = 32767;        // amplitude
	final static float fs = 44100;       // sample rate
	final static float fc = 11025;       // frequency of carrier
	final static int spb = 3;            // samples per bit
	final static int header_size = 200;
	final static int max_retry = 10;
	final static float thresPower = 80;
	final static float thresPowerCoeff = 100;
	final static int thresBack = 1200;
	final static long ack_timeout = 1000;
	int retry = 0;
	int send_num_frames = 0;
	int get_num_frames = 0;
	int retry_cnt = 0;
	Integer sent_so_far = 0;
	Integer received_so_far = 0;

	byte[][] received;
	ArrayBlockingQueue<Integer> ack_to_send =
			new ArrayBlockingQueue<Integer>(1024);
	ArrayBlockingQueue<File> files_to_send =
			new ArrayBlockingQueue<File>(1024);
	boolean[] ack_get;
	boolean[] ack_send = null;
	boolean stopped = false;

	byte[] header = new byte[2*header_size];
	int[] header_ints = new int[header_size];
	byte[] intv = new byte[200];
	byte[][] frame_list;
	byte[][] icmp_frame_list = new byte[100][];
	byte[] dummy_body = new byte[frame_size / 8];
	byte[] ackPayload = new byte[frame_size / 8];
	final int packet_len = (frame_size + 40) * spb;
	SourceDataLine speak;
	TargetDataLine mic;
	Object syncHi = new Object();
	Object syncBye = new Object();

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100.f;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = false;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	public void run() {
		try {

			frame_init();
			device_start();
			Thread pd = new Thread(()->{
				try {
					packet_detect();
				} catch (IOException | InterruptedException e) {
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
			Thread ak = new Thread(()->{
				try {
					ack_sender();
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			});
			ak.start();
			mc.start();
			pd.start();

			Thread.sleep(duration);
			stopped = true;
			ak.join();
			mc.join();
			pd.join();

			device_stop();
			System.out.println("Node stopped!");

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static String bytes_to_hex(byte[] bytes, int offset, int length) {
	    final StringBuilder builder = new StringBuilder();
	    for (int i = offset; i < offset + length; ++i) {
	        builder.append(String.format("%02x", bytes[i]));
	    }
	    return builder.toString();
	}

	public static boolean[] bytes_to_bits(byte[] bytesBuffer) {
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

	public static void bits_to_bytes(boolean[] bitsBuffer, byte[] bytesBuffer) {
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

	public static byte bit8_to_byte(boolean[] bit8, int offset) {
		byte ret = 0;
		for (int i = 0; i < 8; ++i) {
			if (bit8[i + offset]) {
				ret |= 1 << i;
			}
		}
		return ret;
	}

	public static boolean[] byte_to_bit8(byte bt) {
		boolean[] bit8 = new boolean[8];
		for (int i = 0; i < 8; ++i) {
			bit8[i] = (bt & 1) > 0;
			bt >>= 1;
		}
		return bit8;
	}

	public static void int32_to_bytes(int it, byte[] bytes, int offset) {
		for (int i = 0; i < 4; ++i) {
			bytes[i + offset] = (byte) (it & 0xff);
			it >>= 8;
		}
	}

	public static void int16_to_bytes(int it, byte[] bytes, int offset) {
		for (int i = 0; i < 2; ++i) {
			bytes[i + offset] = (byte) (it & 0xff);
			it >>= 8;
		}
	}

	public static void addr_to_bytes(String addr, byte[] bytes, int offset) throws UnknownHostException {
		InetAddress ip = InetAddress.getByName(addr);
		byte[] baddr = ip.getAddress();
		System.arraycopy(baddr, 0, bytes, offset, 4);
	}

	public static String bytes_to_addr(byte[] bytes, int offset) throws UnknownHostException {
		byte[] baddr = Arrays.copyOfRange(bytes, offset, offset + 4);
		return InetAddress.getByAddress(baddr).getHostAddress();
	}

	public static int bytes_to_int32(byte[] bytes, int offset) {
		int it = 0;
		for (int i = 0; i < 4; ++i) {
			it |= (bytes[i + offset] & 0xff) << (8 * i);
		}
		return it;
	}

	public static int bytes_to_int16(byte[] bytes, int offset) {
		int it = 0;
		for (int i = 0; i < 2; ++i) {
			it |= (bytes[i + offset] & 0xff) << (8 * i);
		}
		return it;
	}

	public static void str_to_bytes(String str, byte[] bytes, int offset) {
		byte[] str_bytes = str.getBytes();
		int str_len = str.length();
		System.arraycopy(str_bytes, 0, bytes, offset, str_len);
		bytes[offset + str_len] = 0;
	}

	public static String bytes_to_str(byte[] bytes, int offset) {
		String s = new String();
		for (int i = 0;; ++i) {
			byte b = bytes[offset + i];
			if (b != 0) {
				s += (char) b;
			} else {
				break;
			}
		}
		return s;
	}

	private static void write_bits_analog(ByteArrayOutputStream bos, boolean[] bits) {
		for (int j = 0; j < bits.length; ++j) {
			for (int k = 0; k < spb; ++k) {
				int waveo = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
				bos.write((byte) (waveo & 0xff));
				bos.write((byte) (waveo >> 8));
			}
		}
	}

	private static void write_bytes_analog(ByteArrayOutputStream bos, byte[] bytes) {
		write_bits_analog(bos, bytes_to_bits(bytes));
	}

	private static void write_byte_analog(ByteArrayOutputStream bos, byte bt) {
		write_bits_analog(bos, byte_to_bit8(bt));
	}

	private static byte[] get_mac_header(byte dest, byte src, byte type, int frame_no) {
		byte[] mac_header = {dest, src, type, (byte) frame_no};
		return mac_header;
	}

	private static byte get_crc(byte[] data, int offset, int len) {
		CRC8 crc8 = new CRC8(CRC_POLYNOM, CRC_INITIAL);
		crc8.update(data, offset, len);
		byte crc = (byte) crc8.getValue();
		return crc;
	}

	private static byte get_crc(byte[] data) {
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

	private void send_file_init_inline_rv(File file) throws InterruptedException, IOException {
		while (!notify_rfile.exists()) {
			Thread.sleep(100);
		}
		FileInputStream fis = new FileInputStream(file);
		System.out.println("Start to send: " + file.getName());
		int length = (int) file.length();
		send_num_frames = length * 8 / frame_size;
		System.out.println("File length: " + length);
		System.out.println("Num frames: " + send_num_frames);
		ack_get = new boolean[send_num_frames + 2];
		byte[] macPayload = new byte[frame_size / 8];
		frame_list = new byte[send_num_frames + 2][];

		for (int i = 0; i <= send_num_frames + 1; ++i) {
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_UDP_RECV, i));
			if (i == 0 || i == send_num_frames + 1) {
				int32_to_bytes(send_num_frames, macPayload, 0);
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

	private void send_file_hi() throws InterruptedException {
		boolean ack_got = false;
		while (!ack_got) {
			System.out.println("Sending...");
			for (int i = 0; i < 20; ++i)
				send_frame(0);
			synchronized (syncHi) {
				syncHi.wait(2000);
			}
			synchronized (ack_get) {
				ack_got = ack_get[0];
			}
		}
	}

	private void send_file_bye() throws InterruptedException {
		boolean ack_got = false;
		while (!ack_got) {
			System.out.println("Sending...");
			for (int i = 0; i < 20; ++i)
				send_frame(send_num_frames + 1);
			synchronized (syncBye) {
				syncBye.wait(2000);
			}
			synchronized (ack_get) {
				ack_got = ack_get[send_num_frames + 1];
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

	private static void bytes_to_ints(byte[] data, int[] ints, int offset, int len) {
		for (int i = 0; i < len; ++i)
			ints[i + offset] = (data[2*i+1] << 8) | (data[2*i] & 0xff);
	}

	public void ack_sender() throws IOException, InterruptedException {
		while (!stopped) {
			int frame_no = ack_to_send.take();
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_ACK, frame_no));
			phyPayloadStream.write(ackPayload);
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			byte[] to_send = bos.toByteArray();
			speak.write(to_send, 0, to_send.length);
		}
	}

	public void packet_detect() throws IOException, InterruptedException {
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
	}

	private void packet_ana(boolean[] decoded) throws InterruptedException, IOException {
//		System.out.println("There is a packet");
		byte decoded_bytes[] = new byte[decoded.length / 8];
		bits_to_bytes(decoded, decoded_bytes);
		if (get_crc(decoded_bytes, 0, frame_size / 8 + 4)
				!= decoded_bytes[frame_size / 8 + 4]) {
//			System.out.println("CRC failed!");
			return;
		}

		byte packet_dest = bit8_to_byte(decoded, 0);
		if (packet_dest != NODE_ID) {
			System.out.println("To: " + bit8_to_byte(decoded, 0));
			return;
		}
		byte packet_src = bit8_to_byte(decoded, 8);
		byte type = bit8_to_byte(decoded, 16);
		int frame_no = bit8_to_byte(decoded, 24) & 0xff;
		if (packet_src != NODE_TX) {
			System.out.println("Who is that?");
			return;
		}
		if (type == TYPE_ACK) {
			synchronized (ack_get) {
				boolean new_ack = !ack_get[frame_no];
				if (new_ack) {
					ack_get[frame_no] = true;
					if (frame_no == 0) {
						synchronized (syncHi) {
							syncHi.notify();
						}
					} else if (frame_no == send_num_frames + 1) {
						synchronized (syncBye) {
							syncBye.notify();
						}
					} else {
						synchronized (sent_so_far) {
							sent_so_far++;
						}
					}
				}
			}
		} else if (type == TYPE_UDP_SEND) {
			if (frame_no == 0) {
				if (ack_send == null) {
					get_num_frames = bytes_to_int32(decoded_bytes, 4);
					String filename = bytes_to_str(decoded_bytes, 8);
					file_rx = new File(filename + ".out");
					System.out.println("UDP num: " + get_num_frames);
					System.out.println("filename: " + filename);
					received = new byte[get_num_frames + 1][];
					ack_send = new boolean[get_num_frames + 1];
					ack_send[0] = true;
				}
			} else if (frame_no == get_num_frames + 1) {
				if (ack_send != null) {
					ack_send = null;
					ack_to_send.clear();
					System.out.println("writing file...");
					synchronized (received) {
						if (file_rx == null) {
							return;
						}
						FileOutputStream fos = new FileOutputStream(file_rx);
						for (int no = 1; no <= get_num_frames; no++) {
							if (received[no] != null) {
								String line = bytes_to_str(received[no], 10);
								System.out.println("Line is: " + line);
								fos.write(line.getBytes());
								fos.write('\n');
							} else {
								// shouldn't happen if success
							}
						}
						fos.close();
					}
					System.out.println("file written!");
					notify_file.createNewFile();
					received = null;
				}
			} else {
				if (ack_send == null)
					return;
				synchronized (ack_send) {
					boolean ack_sent = ack_send[frame_no];
					if (!ack_sent) {
						synchronized (received_so_far) {
							received_so_far++;
						}
						ack_send[frame_no] = true;
						received[frame_no] = decoded_bytes;
					}
				}
			}
			// send ack!
			ack_to_send.put(frame_no);
		}
	}

	// send a frame
	private void send_frame(int frame_no) {
		byte[] to_send = frame_list[frame_no];
		speak.write(to_send, 0, to_send.length);
	}

	public void mac() throws InterruptedException, IOException {
		while (!stopped) {
			File file = files_to_send.take();
			send_file_init_inline_rv(file);
			send_file_hi();
			synchronized (sent_so_far) {
				sent_so_far = 0;
			}

			int wait_count = 0;

			while (!stopped) {
				synchronized (sent_so_far) {
					if (sent_so_far == send_num_frames) {
						break;
					}
				}
				for (int i = 1; i <= send_num_frames; ++i) {
					synchronized (ack_get) {
						if (ack_get[i]) {
							continue;
						}
					}
					System.out.println("Send frame " + i);
					if (wait_count >= 5) {
						wait_count = 0;
						System.out.println("Wait! " + i);
						Thread.sleep(1000);
					}
					send_frame(i);
					wait_count++;
				}
			}
			System.out.println("Mac sent a file. bye!");
			send_file_bye();
		}
		System.out.println("Mac has done!");
	}

	public static void main(String[] args) throws InterruptedException {
		final File file = new File("input.txt.bin");
		NodeUDP2 node = new NodeUDP2();
		node.files_to_send.put(file);
		System.out.println("START");
		node.run();
		System.out.println("END");
	}

}
