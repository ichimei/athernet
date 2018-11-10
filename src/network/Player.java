package network;

import java.io.File;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public class Player {

	public void play(File file) {
		try {

			AudioInputStream ais = AudioSystem.getAudioInputStream(file);
			AudioFormat format = ais.getFormat();
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			SourceDataLine speak = (SourceDataLine) AudioSystem.getLine(info);

			speak.open(format);
			speak.start();

			int BUFFER_SIZE = 44100;
			byte[] buffer = new byte[BUFFER_SIZE];

			int bytesRead = 0;
			while (bytesRead != -1) {
				bytesRead = ais.read(buffer, 0, BUFFER_SIZE);
				if (bytesRead != -1)
					speak.write(buffer, 0, bytesRead);
			}

			speak.stop();
			speak.close();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		File file = new File("kirakira.wav");
		Player player = new Player();
		Recorder recorder = new Recorder();
		recorder.start();
		new Thread(new Runnable() {
		     @Override
		     public void run() {
		    	player.play(file);
				recorder.stop();
		     }
		}).start();
		recorder.record();
		recorder.speak();
	}

}
