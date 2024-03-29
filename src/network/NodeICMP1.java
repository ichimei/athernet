package network;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class NodeICMP1 {

	final static boolean RUN_MAC = true;

	final static byte[] BADDR = {10, 20, (byte) 201, 76};

	final static byte TYPE_ICMP_REQ = (byte) 0x00;
	final static byte TYPE_ICMP_REP = (byte) 0xff;
	final static int CRC_POLYNOM = 0x9c;
	final static byte CRC_INITIAL = (byte) 0x00;

	final static byte NODE_ID = (byte) 0x00;
	final static byte NODE_TX = (byte) 0xff;

	final int[] large_buffer = new int[44100 * 600];
	int cur = 0;

	File file_rx = null;

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

	byte[][] icmp_req_received = new byte[65536][];
	byte[][] icmp_rep_received = new byte[65536][];
	ArrayBlockingQueue<Integer> icmp_req_to_send =
			new ArrayBlockingQueue<Integer>(1024);
	ArrayBlockingQueue<Integer> icmp_rep_to_send =
			new ArrayBlockingQueue<Integer>(1024);
	boolean[] icmp_reply = new boolean[100];
	byte[][] icmp_payload = new byte[100][];
	boolean stopped = false;

	byte[] header = new byte[2*header_size];
	int[] header_ints = new int[header_size];
	byte[] intv = new byte[200];
	byte[][] icmp_frame_list;
	final int packet_len = (frame_size + 40) * spb;
	SourceDataLine speak;
	TargetDataLine mic;
	Object sync_icmp = new Object();

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100.f;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = false;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	public void run_icmp() {
		try {

			frame_init();
			device_start();
			Thread irs = new Thread(()->{
				try {
					icmp_reply_sender();
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			});

			Thread pd = new Thread(()->{
				try {
					packet_detect();
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			});
			Thread mc = new Thread(()->{
				try {
					if (RUN_MAC)
						mac_icmp();
				} catch (InterruptedException | IOException e) {
					e.printStackTrace();
				}
			});
			Thread sirq = new Thread(()->{
				try {
					send_icmp_req();
				} catch (InterruptedException | UnknownHostException e) {
					e.printStackTrace();
				}
			});
			irs.start();
			sirq.start();
			pd.start();
			mc.start();

			Thread.sleep(duration);
			stopped = true;
			irs.join();
			sirq.join();
			pd.join();
			mc.join();

			device_stop();
			System.out.println("Node stopped!");

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

//	private static void bytes_to_bits(byte[] bytesBuffer, boolean[] bitsBuffer) {
//		for (int i = 0; i < bytesBuffer.length; ++i) {
//			byte b = bytesBuffer[i];
//			for (int j = 0; j < 8; ++j) {
//				bitsBuffer[i*8+j] = (b & 1) > 0;
//				b >>= 1;
//			}
//		}
//	}

	public static String bytes_to_hex(byte[] bytes, int offset, int length) {
	    final StringBuilder builder = new StringBuilder();
	    for (int i = offset; i < offset + length; ++i) {
	        builder.append(String.format("%02x", bytes[i]));
	    }
	    return builder.toString();
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

	private static void int16_to_bytes_be(int it, byte[] bytes, int offset) {
		bytes[offset] = (byte) (it >> 8);
		bytes[offset + 1] = (byte) (it & 0xff);
	}

	private static void addr_to_bytes(String addr, byte[] bytes, int offset) throws UnknownHostException {
		InetAddress ip = InetAddress.getByName(addr);
		byte[] baddr = ip.getAddress();
		System.arraycopy(baddr, 0, bytes, offset, 4);
	}

	private static String bytes_to_addr(byte[] bytes, int offset) throws UnknownHostException {
		byte[] baddr = Arrays.copyOfRange(bytes, offset, offset + 4);
		return InetAddress.getByAddress(baddr).getHostAddress();
	}

	private static int bytes_to_int32(byte[] bytes, int offset) {
		int it = 0;
		for (int i = 0; i < 4; ++i) {
			it |= (bytes[i + offset] & 0xff) << (8 * i);
		}
		return it;
	}

	private static int bytes_to_int16_be(byte[] bytes, int offset) {
		int it = 0;
		return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
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

	private void send_icmp_init() throws IOException {
		byte[] macPayload = new byte[frame_size / 8];

		for (int i = 0; i < 100; ++i) {
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_ICMP_REQ, 0));
			System.arraycopy(BADDR, 0, macPayload, 0, 4);
			int16_to_bytes_be(i, macPayload, 4);
			Arrays.fill(macPayload, 6, 62, (byte) 48);
			phyPayloadStream.write(macPayload);
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			icmp_frame_list[i] = bos.toByteArray();
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

	public void packet_detect() throws IOException, InterruptedException {
		int buffer_len = 1024;
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
//			System.out.println("To: " + packet_dest);s
			return;
		}
		byte packet_src = bit8_to_byte(decoded, 8);
		byte type = bit8_to_byte(decoded, 16);
		int frame_no = bit8_to_byte(decoded, 24) & 0xff;
		if (type == TYPE_ICMP_REP) {
			int seq = bytes_to_int16_be(decoded_bytes, 8);
			synchronized (icmp_reply) {
				boolean replied = icmp_reply[seq];
				if (!replied) {
					icmp_reply[seq] = true;
					synchronized (icmp_rep_received) {
//						if (icmp_rep_received[seq] != null)
//							return;
						icmp_rep_received[seq] = decoded_bytes;
					}
					synchronized (sync_icmp) {
						sync_icmp.notify();
					}
				}
			}
		} else if (type == TYPE_ICMP_REQ) {
			int seq = bytes_to_int16_be(decoded_bytes, 8);
			synchronized (icmp_req_received) {
//				if (icmp_req_received[seq] != null)
//					return;
				icmp_req_received[seq] = decoded_bytes;
			}
			icmp_rep_to_send.put(seq);
		}
	}

	public void icmp_reply_sender() throws IOException, InterruptedException {
		while (!stopped) {
			int seq = icmp_rep_to_send.take();
			byte[] recv_req;
			synchronized (icmp_req_received) {
				recv_req = icmp_req_received[seq];
			}
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_ICMP_REP, 0));
			phyPayloadStream.write(Arrays.copyOfRange(recv_req, 4, 68));
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			byte[] to_send = bos.toByteArray();
			for (int i = 0; i < 1; ++i)
				speak.write(to_send, 0, to_send.length);
		}
	}

	private void send_frame_icmp_req(int seq) {
		byte[] to_send = icmp_frame_list[seq];
		speak.write(to_send, 0, to_send.length);
	}

	private void send_icmp_req() throws InterruptedException, UnknownHostException {
		while (!stopped) {
			int seq = icmp_req_to_send.take();
			boolean replied;
			for (int i = 0; i < 1; ++i)
				send_frame_icmp_req(seq);
			long start = System.currentTimeMillis();
			synchronized (sync_icmp) {
				sync_icmp.wait(1000);
			}
			long end = System.currentTimeMillis();
			synchronized (icmp_reply) {
				replied = icmp_reply[seq];
			}
			if (!replied)
				System.out.println("Time out!");
			else {
				byte[] received;
				synchronized (icmp_rep_received) {
					received = icmp_rep_received[seq];
				}
				String ip_addr = bytes_to_addr(received, 4);
				System.out.println("Ping success!");
				System.out.println("IP: " + ip_addr);
				System.out.println("Payload: " + bytes_to_hex(received, 10, 56));
				System.out.println("Latency: " + (end - start) + " ms");
			}
		}
	}

	public void mac_icmp() throws InterruptedException, IOException {
//		send_icmp_init();
//		for (int i = 0; i < 10; ++i) {
//			System.out.println("ICMP echo request " + i + "...");
//			icmp_req_to_send.put(i);
//			Thread.sleep(1000);
//		}
		ping_shell();
		System.out.println("MAC stopped!!!");
	}

	public void start_ping(byte[] addr, byte[] payload, int times) throws InterruptedException, IOException {
		byte[] macPayload = new byte[frame_size / 8];
		icmp_reply = new boolean[times];
		icmp_frame_list = new byte[times][];

		for (int i = 0; i < times; ++i) {
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_ICMP_REQ, 0));
			System.arraycopy(addr, 0, macPayload, 0, 4);
			int16_to_bytes_be(i, macPayload, 4);
			System.arraycopy(payload, 0, macPayload, 6, 56);
			phyPayloadStream.write(macPayload);
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			icmp_frame_list[i] = bos.toByteArray();
		}

		for (int i = 0; i < times; ++i) {
			System.out.println("ICMP echo request " + i + "...");
			icmp_req_to_send.put(i);
			Thread.sleep(1000);
		}

		System.out.println("Ping finished!");
	}

	public void ping_shell() throws IOException, InterruptedException {
		Scanner scan = new Scanner(System.in);
		while (true) {
			BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
			System.out.print("> ");
			String s = br.readLine();
			String[] ss = s.strip().split("\\s+");
			if (ss.length != 3) {
				System.out.println("Usage: ADDR PATTERN TIMES");
				continue;
			}
			String adr = ss[0];
			byte[] addr;
			try {
				addr = InetAddress.getByName(adr).getAddress();
			} catch (UnknownHostException e) {
				System.out.println("Address has error");
				continue;
			}
			String timess = ss[2];
			int times;
			try {
				times = Integer.parseInt(timess);
				if (times <= 0 || times >= 65536) {
					throw new NumberFormatException();
				}
			} catch (NumberFormatException e) {
				System.out.println("Number has error");
				continue;
			}
			byte[] pattern = ss[1].toUpperCase().getBytes();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			boolean success = true;
			for (int i = 0; i < pattern.length; i++) {
				if (pattern[i] >= '0' && pattern[i] <= '9') {
					bos.write(pattern[i] - '0');
				} else if (pattern[i] >= 'A' && pattern[i] <= 'F') {
					bos.write(pattern[i] - 'A' + 10);
				} else {
					success = false;
					break;
				}
			}
			byte[] bs = bos.toByteArray();
			if (!success || bs.length == 0) {
				System.out.println("Pattern has error");
				continue;
			}
			byte[] payload = new byte[56];
			for (int i = 0; i < 56; i++) {
				int ind_first = (2 * i) % bs.length;
				int ind_second = (2 * i + 1) % bs.length;
				payload[i] = (byte) (bs[ind_first] << 4 | bs[ind_second]);
			}
			System.out.println("Command Accepted!");
			start_ping(addr, payload, times);
		}
	}

	public static void main(String[] args) throws InterruptedException, IOException {
		NodeICMP1 node = new NodeICMP1();
		System.out.println("START");
		node.run_icmp();
		System.out.println("END");
	}

}
