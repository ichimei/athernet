package network;

import java.io.ByteArrayOutputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public class Recorder {

	AudioFormat format;
	TargetDataLine mic;
	SourceDataLine speak;
	byte record[];
	boolean stopped = true;

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100;
		int sampleSizeInBits = 16;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = true;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	public void record() {

		format = getAudioFormat();
		DataLine.Info infoMic = new DataLine.Info(TargetDataLine.class, format);

		try {
			mic = (TargetDataLine) AudioSystem.getLine(infoMic);
			mic.open(format);
		} catch (LineUnavailableException e) {
			e.printStackTrace();
		}

		ByteArrayOutputStream bos = new ByteArrayOutputStream();

		int BUFFER_SIZE = 100;
		byte[] data = new byte[BUFFER_SIZE];

		mic.start();

		System.out.println("Start recording...");

		while (!stopped) {
			int bytesRead = mic.read(data, 0, BUFFER_SIZE);
			bos.write(data, 0, bytesRead);
		}

		record = bos.toByteArray();

		System.out.println("End recording!");

		mic.stop();
		mic.close();

	}

	public void speak() {

		DataLine.Info infoSpeak = new DataLine.Info(SourceDataLine.class, format);

		try {
			speak = (SourceDataLine) AudioSystem.getLine(infoSpeak);
			speak.open(format);
		} catch (LineUnavailableException e) {
			e.printStackTrace();
		}

		speak.start();

		System.out.println("Start speaking...");

		speak.write(record, 0, record.length);

		System.out.println("End speaking!");

		speak.stop();
		speak.close();

	}

	public void start() {
		stopped = false;
	}

	public void stop() {
		stopped = true;
	}

	public static void main(String[] args) {
		long time = 10000;
		Recorder recorder = new Recorder();
		recorder.start();
		new Thread(new Runnable() {
		     @Override
		     public void run() {
		    	 try {
					Thread.sleep(time);
					recorder.stop();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
		     }
		}).start();
		recorder.record();
		recorder.speak();
	}

}
