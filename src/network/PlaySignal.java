package network;

import java.io.ByteArrayInputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public class PlaySignal {

	AudioFormat format;
	static int amp = 63;
	static int fs = 44100;
	static int maxTime = 10;
	byte[] data = new byte[maxTime*fs];

	private AudioFormat getAudioFormat() {
		float sampleRate = 44100;
		int sampleSizeInBits = 8;
		int channels = 1;
		boolean signed = true;
		boolean bigEndian = true;
		return new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);
	}

	public void play() {
		try {

			for (int i = 0; i < data.length; ++i) {
				data[i] = (byte) ((Math.sin(2*Math.PI*1000*i/fs) + Math.sin(2*Math.PI*10000*i/fs)) * amp);
			}

			format = getAudioFormat();
//			AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), format, data.length), AudioFileFormat.Type.WAVE, new File("playsignal.wav"));
//			return;

			ByteArrayInputStream bis = new ByteArrayInputStream(data);
			DataLine.Info infoSpeak = new DataLine.Info(SourceDataLine.class, format);
			SourceDataLine speak = (SourceDataLine) AudioSystem.getLine(infoSpeak);

			speak.open(format);
			speak.start();

			System.out.println("Start speaking...");

			int BUFFER_SIZE = 44100;
			byte[] buffer = new byte[BUFFER_SIZE];

			int bytesRead = 0;
			while (bytesRead != -1) {
				bytesRead = bis.read(buffer, 0, BUFFER_SIZE);
				if (bytesRead != -1)
					speak.write(buffer, 0, bytesRead);
			}

			System.out.println("End speaking!");

			speak.stop();
			speak.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		PlaySignal playsig = new PlaySignal();
		playsig.play();
	}

}
