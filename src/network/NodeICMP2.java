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

public class NodeICMP2 {

	final static File file_req = new File("icmp_req.bin");
	final static File file_rep = new File("icmp_rep.bin");
	final static File file_req_notify = new File("icmp_req.bin.notify");
	final static File file_rep_notify = new File("icmp_rep.bin.notify");
	final static File file_req2 = new File("icmp_req2.bin");
	final static File file_rep2 = new File("icmp_rep2.bin");
	final static File file_req2_notify = new File("icmp_req2.bin.notify");
	final static File file_rep2_notify = new File("icmp_rep2.bin.notify");

	final static byte[] BADDR = {119, 75, (byte) 217, 26};

	final static byte TYPE_ICMP_REQ = (byte) 0x00;
	final static byte TYPE_ICMP_REP = (byte) 0xff;
	final static int CRC_POLYNOM = 0x9c;
	final static byte CRC_INITIAL = (byte) 0x00;

	final static byte NODE_ID = (byte) 0xff;
	final static byte NODE_TX = (byte) 0x00;

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
	ArrayBlockingQueue<Integer> icmp_rep_to_int =
			new ArrayBlockingQueue<Integer>(1024);
	ArrayBlockingQueue<Integer> icmp_rep_to_send =
			new ArrayBlockingQueue<Integer>(1024);
	boolean[] icmp_reply = new boolean[100];
	byte[][] icmp_payload = new byte[100][];
	boolean stopped = false;

	byte[] header = new byte[2*header_size];
	int[] header_ints = new int[header_size];
	byte[] intv = new byte[200];
	byte[][] icmp_frame_list = new byte[100][];
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
			Thread pd = new Thread(()->{
				try {
					packet_detect();
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			});
			Thread irs = new Thread(()->{
				try {
					icmp_reply_sender();
				} catch (InterruptedException | IOException e) {
					e.printStackTrace();
				}
			});
			Thread irr = new Thread(()->{
				try {
					icmp_request_receiver();
				} catch (InterruptedException | IOException e) {
					e.printStackTrace();
				}
			});
			Thread irti = new Thread(()->{
				try {
					icmp_reply_to_internet();
				} catch (InterruptedException | IOException e) {
					e.printStackTrace();
				}
			});
			irr.start();
			irti.start();
			irs.start();
			pd.start();

			Thread.sleep(duration);
			stopped = true;
			irr.join();
			irti.join();
			irs.join();
			pd.join();

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

	private static void int16_to_bytes(int it, byte[] bytes, int offset) {
		for (int i = 0; i < 2; ++i) {
			bytes[i + offset] = (byte) (it & 0xff);
			it >>= 8;
		}
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

	private static void str_to_bytes(String str, byte[] bytes, int offset) {
		byte[] str_bytes = str.getBytes();
		int str_len = str.length();
		System.arraycopy(str_bytes, 0, bytes, offset, str_len);
		bytes[offset + str_len] = 0;
	}

	private static String bytes_to_str(byte[] bytes, int offset) {
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

//	private int[] bits_to_ints(boolean[] bits) {
//		int[] ints = new int[spb*bits.length];
//		for (int j = 0; j < bits.length; ++j) {
//			for (int k = 0; k < spb; ++k) {
//				ints[j*spb+k] = (int) (amp * Math.sin(2 * Math.PI * fc * k / fs) * (bits[j] ? 1 : -1));
//			}
//		}
//		return ints;
//	}

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

	public void icmp_reply_to_internet() throws InterruptedException, IOException {

		while (!stopped) {
			int seq = icmp_rep_to_int.take();
			byte[] recv_rep;
			synchronized (icmp_rep_received) {
				recv_rep = icmp_rep_received[seq];
			}
			FileOutputStream fos = new FileOutputStream(file_rep2);
			fos.write(Arrays.copyOfRange(recv_rep, 4, 66));
			fos.close();
			file_rep2_notify.createNewFile();
		}
	}

	public void icmp_reply_sender() throws IOException, InterruptedException {

		while (!stopped) {
			int seq = icmp_rep_to_send.take();
			System.out.println("This is " + seq);
			byte[] recv_req;
			synchronized (icmp_req_received) {
				recv_req = icmp_req_received[seq];
			}
//			System.out.println(recv_req.length);
			FileOutputStream fos = new FileOutputStream(file_req);
			fos.write(recv_req, 4, 62);
			fos.close();
			file_req_notify.createNewFile();
			while (!file_rep_notify.exists()) {
				Thread.sleep(50);
			}
			file_rep_notify.delete();
			FileInputStream fis = new FileInputStream(file_rep);
			byte[] recv_rep = fis.readAllBytes();
			fis.close();
			if (recv_rep.length == 0)
				continue;
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_ICMP_REP, 0));
			phyPayloadStream.write(recv_rep);
			phyPayloadStream.write(new byte[frame_size / 8 - recv_rep.length]);
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			byte[] to_send = bos.toByteArray();
			for (int i = 0; i < 1; ++i)
				speak.write(to_send, 0, to_send.length);
		}
	}

	public void icmp_request_receiver() throws InterruptedException, IOException {

		while (!stopped) {
			while (!file_req2_notify.exists()) {
				Thread.sleep(50);
			}
			file_req2_notify.delete();
			FileInputStream fis = new FileInputStream(file_req2);
			byte[] recv_req = fis.readAllBytes();
//			int seq = bytes_to_int16_be(recv_req, 4);
			fis.close();
			ByteArrayOutputStream phyPayloadStream = new ByteArrayOutputStream();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			phyPayloadStream.write(get_mac_header(NODE_TX, NODE_ID, TYPE_ICMP_REQ, 0));
			phyPayloadStream.write(recv_req);
			phyPayloadStream.write(new byte[frame_size / 8 - recv_req.length]);
			byte[] phyPayload = phyPayloadStream.toByteArray();
			bos.write(header);
			write_bytes_analog(bos, phyPayload);
			write_byte_analog(bos, get_crc(phyPayload));
			byte[] to_send = bos.toByteArray();
			for (int i = 0; i < 1; ++i)
				speak.write(to_send, 0, to_send.length);
		}
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
			System.out.println("To: " + packet_dest);
			return;
		}
		byte packet_src = bit8_to_byte(decoded, 8);
		byte type = bit8_to_byte(decoded, 16);
		int frame_no = bit8_to_byte(decoded, 24) & 0xff;
		if (type == TYPE_ICMP_REP) {
			int seq = bytes_to_int16_be(decoded_bytes, 8);
			synchronized (icmp_rep_received) {
//				if (icmp_rep_received[seq] != null)
//					return;
				icmp_rep_received[seq] = decoded_bytes;
			}
			icmp_rep_to_int.put(seq);
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

	public static void main(String[] args) throws InterruptedException {
		NodeICMP2 node = new NodeICMP2();
		System.out.println("START");
		node.run_icmp();
		System.out.println("END");
	}

}
