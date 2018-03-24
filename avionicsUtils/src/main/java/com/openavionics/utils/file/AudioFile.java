package com.openavionics.utils.file;

import java.io.File;
import java.io.IOException;

public interface AudioFile {
	void open(File file) throws IOException, AudioFileException;
	void create(File file, int numChannels, long numFrames, int validBits, long sampleRate) throws IOException, AudioFileException;
	void close() throws IOException;
	boolean valid(File file);

	File getFile();
	String getPath();

	int getNumChannels();
	long getNumFrames();

	void seekToFrame(long frame) throws IOException, AudioFileException;
	long currentFrame() throws IOException, AudioFileException;
	
	int readFrames(short[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException;
	int readFrames(short[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException;
	int readFrames(float[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException;
	int readFrames(float[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException;
	int readFrames(double[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException;
	int readFrames(double[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException;
	
	int writeFrames(short[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException;
	int writeFrames(short[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException;
	int writeFrames(float[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException;
	int writeFrames(float[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException;
	int writeFrames(double[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException;
	int writeFrames(double[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException;

	enum Type { WAVE, FLAC, APE, MP3 }
	enum IOState {READING, WRITING, CLOSED};

}
