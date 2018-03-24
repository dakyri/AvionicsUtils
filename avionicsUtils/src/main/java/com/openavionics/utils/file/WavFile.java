package com.openavionics.utils.file;

// Wav file IO class
// A.Greensted
// http://www.labbookpages.co.uk

// File format is based on the information from
// http://www.sonicspot.com/guide/wavefiles.html
// http://www.blitter.com/~russtopia/MIDI/~jglatt/tech/wave.htm

// Version 1.0

// modified to add short reads 

import java.io.*;
import java.nio.channels.FileChannel;

import android.util.Log;

public class WavFile implements AudioFile
{
	private final static int BUFFER_SIZE = 4096;

	private final static int FMT_CHUNK_ID = 0x20746D66;
	private final static int DATA_CHUNK_ID = 0x61746164;
	private final static int RIFF_CHUNK_ID = 0x46464952;
	private final static int RIFF_TYPE_ID = 0x45564157;

	private File file;						// File that will be read from or written to
	private IOState ioState;				// Specifies the IO State of the Wav File (used for snaity checking)
	private int bytesPerSample;			// Number of bytes required to store a single sample
	private long numFrames;					// Number of frames within the data section
	private FileOutputStream oStream;	// Output stream used for writting data
	private FileInputStream iStream;		// Input stream used for reading data
	private double doubleScale;				// Scaling factor used for int <-> float conversion				
	private double doubleOffset;			// Offset factor used for int <-> float conversion				
	private boolean wordAlignAdjust;		// Specify if an extra byte at the end of the data chunk is required for word alignment

	// Wav Header
	private int numChannels;				// 2 bytes unsigned, 0x0001 (1) to 0xFFFF (65,535)
	private long sampleRate;				// 4 bytes unsigned, 0x00000001 (1) to 0xFFFFFFFF (4,294,967,295)
													// Although a java int is 4 bytes, it is signed, so need to use a long
	private int blockAlign;					// 2 bytes unsigned, 0x0001 (1) to 0xFFFF (65,535)
	private int validBits;					// 2 bytes unsigned, 0x0002 (2) to 0xFFFF (65,535)

	// Buffering
	private byte[] buffer;					// Local buffer used for IO
	private int bufferPointer;				// Points to the current position in local buffer
	private int bytesRead;					// Bytes read after last read into local buffer
	private long frameCounter;				// Current number of frames read or written
	
	private long dataStart;

	// Cannot instantiate WavFile directly, must either use create() or open()
	public WavFile()
	{
		buffer = new byte[BUFFER_SIZE];
		ioState = IOState.CLOSED;
		dataStart = 0;
	}

	@Override
	public File getFile() {
		return file;
	}

	@Override
	public String getPath() {
		return file != null? file.getPath():"<null>";
	}

	public int getNumChannels()
	{
		return numChannels;
	}

	public long getNumFrames()
	{
		return numFrames;
	}

	public long getFramesRemaining()
	{
		return numFrames - frameCounter;
	}

	public long getSampleRate()
	{
		return sampleRate;
	}

	public int getValidBits()
	{
		return validBits;
	}

	public void create(File file, int numChannels, long numFrames, int validBits, long sampleRate) throws IOException, AudioFileException
	{
		if (ioState != IOState.CLOSED) {
			close();
		}

		this.file = file;
		this.numChannels = numChannels;
		this.numFrames = numFrames;
		this.sampleRate = sampleRate;
		this.bytesPerSample = (validBits + 7) / 8;
		this.blockAlign = this.bytesPerSample * numChannels;
		this.validBits = validBits;

		// Sanity check arguments
		if (numChannels < 1 || numChannels > 65535) throw new AudioFileException("Illegal number of channels, valid range 1 to 65536");
		if (numFrames < 0) throw new AudioFileException("Number of frames must be positive");
		if (validBits < 2 || validBits > 65535) throw new AudioFileException("Illegal number of valid bits, valid range 2 to 65536");
		if (sampleRate < 0) throw new AudioFileException("Sample rate must be positive");

		// Create output stream for writing data
		this.oStream = new FileOutputStream(file);
		
		writeHeader(this);

		// Calculate the scaling factor for converting to a normalised double
		if (this.validBits > 8) {
			// If more than 8 validBits, data is signed
			// Conversion required multiplying by magnitude of max positive value
			this.doubleOffset = 0;
			this.doubleScale = Long.MAX_VALUE >> (64 - this.validBits);
		} else {
			// Else if 8 or less validBits, data is unsigned
			// Conversion required dividing by max positive value
			this.doubleOffset = 1;
			this.doubleScale = 0.5 * ((1 << this.validBits) - 1);
		}

		// Finally, set the IO State
		this.bufferPointer = 0;
		this.bytesRead = 0;
		this.frameCounter = 0;
		this.ioState = IOState.WRITING;
	}

	private static void writeHeader(WavFile wavFile) throws IOException
	{
		// Calculate the chunk sizes
		long dataChunkSize = wavFile.blockAlign * wavFile.numFrames;
		long mainChunkSize =	4 +	// Riff Type
									8 +	// Format ID and size
									16 +	// Format data
									8 + 	// Data ID and size
									dataChunkSize;

		// Chunks must be word aligned, so if odd number of audio data bytes
		// adjust the main chunk size
		if (dataChunkSize % 2 == 1) {
			mainChunkSize += 1;
			wavFile.wordAlignAdjust = true;
		}
		else {
			wavFile.wordAlignAdjust = false;
		}

		// Set the main chunk size
		putLE(RIFF_CHUNK_ID,	wavFile.buffer, 0, 4);
		putLE(mainChunkSize,	wavFile.buffer, 4, 4);
		putLE(RIFF_TYPE_ID,	wavFile.buffer, 8, 4);

		// Write out the header
		wavFile.oStream.write(wavFile.buffer, 0, 12);

		// Put format data in buffer
		long averageBytesPerSecond = wavFile.sampleRate * wavFile.blockAlign;

		putLE(FMT_CHUNK_ID,				wavFile.buffer, 0, 4);		// Chunk ID
		putLE(16,							wavFile.buffer, 4, 4);		// Chunk Data Size
		putLE(1,								wavFile.buffer, 8, 2);		// Compression Code (Uncompressed)
		putLE(wavFile.numChannels,				wavFile.buffer, 10, 2);		// Number of channels
		putLE(wavFile.sampleRate,					wavFile.buffer, 12, 4);		// Sample Rate
		putLE(averageBytesPerSecond,	wavFile.buffer, 16, 4);		// Average Bytes Per Second
		putLE(wavFile.blockAlign,		wavFile.buffer, 20, 2);		// Block Align
		putLE(wavFile.validBits,					wavFile.buffer, 22, 2);		// Valid Bits

		// Write Format Chunk
		wavFile.oStream.write(wavFile.buffer, 0, 24);

		// Start Data Chunk
		putLE(DATA_CHUNK_ID,				wavFile.buffer, 0, 4);		// Chunk ID
		putLE(dataChunkSize,				wavFile.buffer, 4, 4);		// Chunk Data Size

		// Write Format Chunk
		wavFile.oStream.write(wavFile.buffer, 0, 8);
	}

	public void open(File file) throws IOException, AudioFileException
	{
		if (ioState != IOState.CLOSED) {
			close();
		}
		// Instantiate new Wavfile and store the file reference
		this.file = file;

		// Create a new file input stream for reading file data
		iStream = new FileInputStream(file);

		// Read the first 12 bytes of the file
		int bytesRead = iStream.read(buffer, 0, 12);
		if (bytesRead != 12) throw new AudioFileException("Not enough wav file bytes for header");

		// Extract parts from the header
		long riffChunkID = getLE(buffer, 0, 4);
		long chunkSize = getLE(buffer, 4, 4);
		long riffTypeID = getLE(buffer, 8, 4);

		// Check the header bytes contains the correct signature
		if (riffChunkID != RIFF_CHUNK_ID) throw new AudioFileException("Invalid Wav Header data, incorrect riff chunk ID");
		if (riffTypeID != RIFF_TYPE_ID) throw new AudioFileException("Invalid Wav Header data, incorrect riff type ID");

		// Check that the file size matches the number of bytes listed in header
		if (file.length() != chunkSize+8) {
			throw new AudioFileException("Header chunk size (" + chunkSize + ") does not match file size (" + file.length() + ")");
		}

		boolean foundFormat = false;
		boolean foundData = false;

		// Search for the Format and Data Chunks
		while (true) {
			// Read the first 8 bytes of the chunk (ID and chunk size)
			bytesRead = iStream.read(buffer, 0, 8);
			if (bytesRead == -1) throw new AudioFileException("Reached end of file without finding format chunk");
			if (bytesRead != 8) throw new AudioFileException("Could not read chunk header");

			// Extract the chunk ID and Size
			long chunkID = getLE(buffer, 0, 4);
			chunkSize = getLE(buffer, 4, 4);

			// Word align the chunk size
			// chunkSize specifies the number of bytes holding data. However,
			// the data should be word aligned (2 bytes) so we need to calculate
			// the actual number of bytes in the chunk
			long numChunkBytes = (chunkSize%2 == 1) ? chunkSize+1 : chunkSize;

			if (chunkID == FMT_CHUNK_ID) {
				// Flag that the format chunk has been found
				foundFormat = true;

				// Read in the header info
				bytesRead = iStream.read(buffer, 0, 16);

				// Check this is uncompressed data
				int compressionCode = (int) getLE(buffer, 0, 2);
				if (compressionCode != 1) throw new AudioFileException("Compression Code " + compressionCode + " not supported");

				// Extract the format information
				numChannels = (int) getLE(buffer, 2, 2);
				sampleRate = getLE(buffer, 4, 4);
				blockAlign = (int) getLE(buffer, 12, 2);
				validBits = (int) getLE(buffer, 14, 2);

				if (numChannels == 0) throw new AudioFileException("Number of channels specified in header is equal to zero");
				if (blockAlign == 0) throw new AudioFileException("Block Align specified in header is equal to zero");
				if (validBits < 2) throw new AudioFileException("Valid Bits specified in header is less than 2");
				if (validBits > 64) throw new AudioFileException("Valid Bits specified in header is greater than 64, this is greater than a long can hold");

				// Calculate the number of bytes required to hold 1 sample
				bytesPerSample = (validBits + 7) / 8;
				if (bytesPerSample * numChannels != blockAlign)
					throw new AudioFileException("Block Align does not agree with bytes required for validBits and number of channels");

				// Account for number of format bytes and then skip over
				// any extra format bytes
				numChunkBytes -= 16;
				if (numChunkBytes > 0) iStream.skip(numChunkBytes);
			} else if (chunkID == DATA_CHUNK_ID) {
				// Check if we've found the format chunk,
				// If not, throw an exception as we need the format information
				// before we can read the data chunk
				if (foundFormat == false) throw new AudioFileException("Data chunk found before Format chunk");

				// Check that the chunkSize (wav data length) is a multiple of the
				// block align (bytes per frame)
				if (chunkSize % blockAlign != 0) throw new AudioFileException("Data Chunk size is not multiple of Block Align");

				// Calculate the number of frames
				numFrames = chunkSize / blockAlign;
				
				// Flag that we've found the wave data chunk
				foundData = true;

				break;
			} else {
				// If an unknown chunk ID is found, just skip over the chunk data
				iStream.skip(numChunkBytes);
			}
		}
		
		dataStart = iStream.getChannel().position();

		// Throw an exception if no data chunk has been found
		if (foundData == false) throw new AudioFileException("Did not find a data chunk");

		// Calculate the scaling factor for converting to a normalised double
		if (validBits > 8) {
			// If more than 8 validBits, data is signed
			// Conversion required dividing by magnitude of max negative value
			doubleOffset = 0;
			doubleScale = 1 << (validBits - 1);
		} else {
			// Else if 8 or less validBits, data is unsigned
			// Conversion required dividing by max positive value
			doubleOffset = -1;
			doubleScale = 0.5 * ((1 << validBits) - 1);
		}

		bufferPointer = 0;
		bytesRead = 0;
		frameCounter = 0;
		ioState = IOState.READING;
	}


	@Override
	public void seekToFrame(long frame) throws IOException, AudioFileException
	{
		Log.d("wave file", String.format("seek to frame %d + %d * %d  * %d", dataStart, bytesPerSample, numChannels, frame));
		if (dataStart <= 0) {
			throw new AudioFileException("Wave seek, data start unknown");
		}
		if (frame < 0) {
			throw new AudioFileException("Wave seek, invalid frame requested");
		}
		FileChannel c = iStream.getChannel();
		long cp = c.position() - bytesRead + bufferPointer;
		long frs = dataStart+(frame*bytesPerSample*numChannels);
		if (frs == cp) {
			Log.d("wave file", "seek frame is in the right spot!");
			return;
		}
		if (frs >= (c.position()-bytesRead) && frs <= c.position()) {
			Log.d("wave file", "seek frame is at least in the same buffer!");
			bufferPointer = (int) (frs-c.position()+bytesRead);
			return;
		}
		c.position(frs);
		Log.d("wave file", String.format("position %d", frs));
		bufferPointer = bytesRead = 0;
		frameCounter = frame;
	}
	
	@Override
	public long currentFrame() throws IOException, AudioFileException
	{
		if (dataStart <= 0) {
			throw new AudioFileException("Wave seek, data start unknown");
		}
		long p = iStream.getChannel().position() - bytesRead + bufferPointer;
		if (p <= 0) {
			throw new AudioFileException("Wave seek, invalid frame calculated");
		}
		return p/(bytesPerSample*numChannels);
	}
	
	/**
	 * Get and Put little endian data from local buffer
	 * @param buffer
	 * @param pos
	 * @param numBytes
	 * @return
	 */
	private static long getLE(byte[] buffer, int pos, int numBytes)
	{
		numBytes --;
		pos += numBytes;

		long val = buffer[pos] & 0xFF;
		for (int b=0 ; b<numBytes ; b++) val = (val << 8) + (buffer[--pos] & 0xFF);

		return val;
	}

	private static void putLE(long val, byte[] buffer, int pos, int numBytes)
	{
		for (int b=0 ; b<numBytes ; b++)
		{
			buffer[pos] = (byte) (val & 0xFF);
			val >>= 8;
			pos ++;
		}
	}

	// Sample Writing and Reading
	// --------------------------
	private void writeSample(long val) throws IOException
	{
		for (int b=0 ; b<bytesPerSample ; b++) {
			if (bufferPointer == BUFFER_SIZE) {
				oStream.write(buffer, 0, BUFFER_SIZE);
				bufferPointer = 0;
			}

			buffer[bufferPointer] = (byte) (val & 0xFF);
			val >>= 8;
			bufferPointer ++;
		}
	}

	private long readSample() throws IOException, AudioFileException
	{
		long val = 0;
		for (int b=0 ; b<bytesPerSample ; b++) {
			if (bufferPointer == bytesRead) {
				int read = iStream.read(buffer, 0, BUFFER_SIZE);
//				Log.d("wave", String.format("read %d", read));
				if (read == -1) throw new AudioFileException("Not enough data available");
				bytesRead = read;
				bufferPointer = 0;
			}

			int v = buffer[bufferPointer];
			if (b < bytesPerSample-1 || bytesPerSample == 1) v &= 0xFF;
			val += v << (b * 8);

			bufferPointer ++;
		}

		return val;
	}

	// Short
	// -------
	public int readFrames(short[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(short[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f=0 ; f<numFramesToRead ; f++) {
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++) {
				sampleBuffer[offset] = (short) readSample();
				offset ++;
			}

			frameCounter ++;
		}

		return numFramesToRead;
	}


	@Override
	public int writeFrames(short[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	@Override
	public int writeFrames(short[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++) {
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++){
				writeSample(sampleBuffer[offset]);
				offset ++;
			}
			frameCounter ++;
		}

		return numFramesToWrite;
	}

	// Float
	// ------
	public int readFrames(float[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(float[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");
		for (int f=0 ; f<numFramesToRead ; f++)	{
			if (frameCounter == numFrames) return f;
			for (int c=0 ; c<numChannels ; c++)	{
				sampleBuffer[offset] = (float) (doubleOffset + (double) readSample() / doubleScale);
				offset ++;
			}
			frameCounter ++;
		}
		return numFramesToRead;
	}

	@Override
	public int writeFrames(float[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException {
		throw new AudioFileException("Write float unimplemented");
	}

	@Override
	public int writeFrames(float[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException {
		throw new AudioFileException("Write float unimplemented");
	}
	
	// Integer
	// -------
	public int readFrames(int[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(int[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f=0 ; f<numFramesToRead ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++)
			{
				sampleBuffer[offset] = (int) readSample();
				offset ++;
			}

			frameCounter ++;
		}

		return numFramesToRead;
	}

	public int readFrames(int[][] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(int[][] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f=0 ; f<numFramesToRead ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++) sampleBuffer[c][offset] = (int) readSample();

			offset ++;
			frameCounter ++;
		}

		return numFramesToRead;
	}

	public int writeFrames(int[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	public int writeFrames(int[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++)
			{
				writeSample(sampleBuffer[offset]);
				offset ++;
			}

			frameCounter ++;
		}

		return numFramesToWrite;
	}

	public int writeFrames(int[][] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	public int writeFrames(int[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++) writeSample(sampleBuffer[c][offset]);

			offset ++;
			frameCounter ++;
		}

		return numFramesToWrite;
	}

	// Long
	// ----
	public int readFrames(long[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(long[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f=0 ; f<numFramesToRead ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++)
			{
				sampleBuffer[offset] = readSample();
				offset ++;
			}

			frameCounter ++;
		}

		return numFramesToRead;
	}

	public int readFrames(long[][] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(long[][] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f=0 ; f<numFramesToRead ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++) sampleBuffer[c][offset] = readSample();

			offset ++;
			frameCounter ++;
		}

		return numFramesToRead;
	}

	public int writeFrames(long[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	public int writeFrames(long[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++)
			{
				writeSample(sampleBuffer[offset]);
				offset ++;
			}

			frameCounter ++;
		}

		return numFramesToWrite;
	}

	public int writeFrames(long[][] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	public int writeFrames(long[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++) writeSample(sampleBuffer[c][offset]);

			offset ++;
			frameCounter ++;
		}

		return numFramesToWrite;
	}

	// Double
	// ------
	public int readFrames(double[] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(double[] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");
		for (int f=0 ; f<numFramesToRead ; f++)	{
			if (frameCounter == numFrames) return f;
			for (int c=0 ; c<numChannels ; c++)	{
				sampleBuffer[offset] = doubleOffset + (double) readSample() / doubleScale;
				offset ++;
			}
			frameCounter ++;
		}
		return numFramesToRead;
	}

	public int readFrames(double[][] sampleBuffer, int numFramesToRead) throws IOException, AudioFileException
	{
		return readFrames(sampleBuffer, 0, numFramesToRead);
	}

	public int readFrames(double[][] sampleBuffer, int offset, int numFramesToRead) throws IOException, AudioFileException
	{
		if (ioState != IOState.READING) throw new IOException("Cannot read from WavFile instance");

		for (int f=0 ; f<numFramesToRead ; f++)	{
			if (frameCounter == numFrames) return f;
			for (int c=0 ; c<numChannels ; c++) sampleBuffer[c][offset] = doubleOffset + (double) readSample() / doubleScale;
			offset ++;
			frameCounter ++;
		}
		return numFramesToRead;
	}

	public int writeFrames(double[] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	public int writeFrames(double[] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++)
			{
				writeSample((long) (doubleScale * (doubleOffset + sampleBuffer[offset])));
				offset ++;
			}

			frameCounter ++;
		}

		return numFramesToWrite;
	}

	public int writeFrames(double[][] sampleBuffer, int numFramesToWrite) throws IOException, AudioFileException
	{
		return writeFrames(sampleBuffer, 0, numFramesToWrite);
	}

	public int writeFrames(double[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException, AudioFileException
	{
		if (ioState != IOState.WRITING) throw new IOException("Cannot write to WavFile instance");

		for (int f=0 ; f<numFramesToWrite ; f++)
		{
			if (frameCounter == numFrames) return f;

			for (int c=0 ; c<numChannels ; c++) writeSample((long) (doubleScale * (doubleOffset + sampleBuffer[c][offset])));

			offset ++;
			frameCounter ++;
		}

		return numFramesToWrite;
	}


	public void close() throws IOException
	{
		if (iStream != null){	// Close the input stream and set to null
			iStream.close();
			iStream = null;
		}

		if (oStream != null) {			
			if (bufferPointer > 0) oStream.write(buffer, 0, bufferPointer); // Write out anything still in the local buffer
			if (wordAlignAdjust) oStream.write(0); // If an extra byte is required for word alignment, add it to the end

			if (numFrames != frameCounter) {
				numFrames = frameCounter;
				FileChannel c = oStream.getChannel();
				c.position(0);
				writeHeader(this);
			}

			oStream.close();
			oStream = null;
		}

		ioState = IOState.CLOSED;		// Flag that the stream is closed
	}

	@Override
	public boolean valid(File file) {
		if (file == null) return false;
		String name = file.getName();
		if (name.matches(".*\\.(wav|WAV)")) return true;
		return false;
	}

	public void display()
	{
		display(System.out);
	}

	public void display(PrintStream out)
	{
		out.printf("File: %s\n", file);
		out.printf("Channels: %d, Frames: %d\n", numChannels, numFrames);
		out.printf("IO State: %s\n", ioState);
		out.printf("Sample Rate: %d, Block Align: %d\n", sampleRate, blockAlign);
		out.printf("Valid Bits: %d, Bytes per sample: %d\n", validBits, bytesPerSample);
	}
}
