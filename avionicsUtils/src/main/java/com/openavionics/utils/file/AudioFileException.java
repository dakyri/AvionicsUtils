package com.openavionics.utils.file;

public class AudioFileException extends Exception
{
	public AudioFileException()
	{
		super();
	}

	public AudioFileException(String message)
	{
		super(message);
	}

	public AudioFileException(String message, Throwable cause)
	{
		super(message, cause);
	}

	public AudioFileException(Throwable cause) 
	{
		super(cause);
	}
}
