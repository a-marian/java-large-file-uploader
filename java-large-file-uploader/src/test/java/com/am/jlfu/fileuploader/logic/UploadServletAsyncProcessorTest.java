package com.am.jlfu.fileuploader.logic;


import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.lessThan;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;

import org.apache.commons.fileupload.FileUploadException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.web.multipart.MultipartFile;

import com.am.jlfu.fileuploader.exception.IncorrectRequestException;
import com.am.jlfu.fileuploader.exception.InvalidCrcException;
import com.am.jlfu.fileuploader.exception.MissingParameterException;
import com.am.jlfu.fileuploader.json.FileStateJson;
import com.am.jlfu.fileuploader.logic.UploadProcessorTest.TestFileSplitResult;
import com.am.jlfu.fileuploader.logic.UploadServletAsyncProcessor.WriteChunkCompletionListener;
import com.am.jlfu.fileuploader.utils.CRCHelper;
import com.am.jlfu.fileuploader.web.utils.FileUploadConfiguration;
import com.am.jlfu.fileuploader.web.utils.RequestComponentContainer;
import com.am.jlfu.staticstate.StaticStateManager;
import com.am.jlfu.staticstate.entities.StaticStatePersistedOnFileSystemEntity;



@ContextConfiguration(locations = { "classpath:jlfu.test.xml" })
@RunWith(SpringJUnit4ClassRunner.class)
public class UploadServletAsyncProcessorTest {

	private static final Logger log = LoggerFactory.getLogger(UploadServletAsyncProcessorTest.class);

	@Autowired
	CRCHelper crcHelper;

	@Autowired
	UploadServletAsyncProcessor uploadServletAsyncProcessor;

	@Autowired
	UploadProcessor uploadProcessor;

	@Autowired
	RequestComponentContainer requestComponentContainer;


	@Autowired
	StaticStateManager<StaticStatePersistedOnFileSystemEntity> staticStateManager;


	MockMultipartFile tinyFile;
	Long tinyFileSize;

	String fileName = "zenameofzefile.owf";



	@Before
	public void init()
			throws IOException, InterruptedException, ExecutionException, TimeoutException {

		// populate request component container
		requestComponentContainer.populate(new MockHttpServletRequest(), new MockHttpServletResponse());

		// clear state
		staticStateManager.clear();

		// init file
		byte[] content = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
		tinyFile = new MockMultipartFile("blob", content);
		tinyFileSize = Integer.valueOf(content.length).longValue();
	}



	private class Listener
			implements WriteChunkCompletionListener {

		private Semaphore waitForMe;



		public Listener(Semaphore waitForMe) {
			this.waitForMe = waitForMe;
		}


		@Override
		public void error(Exception exception) {
			throw new RuntimeException(exception);
		}


		@Override
		public void success() {
			waitForMe.release();
		}

	}



	@Test
	public void testInvalidCrc()
			throws IOException, IncorrectRequestException, MissingParameterException, FileUploadException, InvalidCrcException, InterruptedException {

		// begin a file upload process
		String fileId = uploadProcessor.prepareUpload(tinyFileSize, fileName);

		// upload with bad crc
		TestFileSplitResult splitResult = UploadProcessorTest.getByteArrayFromFile(tinyFile, 0, 3);
		final Semaphore semaphore = new Semaphore(0);
		uploadServletAsyncProcessor.process(fileId, "lala", splitResult.stream, new Listener(semaphore) {

			@Override
			public void error(Exception exception) {
				Assert.assertTrue(exception instanceof InvalidCrcException);
				semaphore.release();
			}


			@Override
			public void success() {
				throw new RuntimeException();
			}

		});


		Assert.assertTrue(semaphore.tryAcquire(5, TimeUnit.SECONDS));
	}


	@Test
	public void testClassicGranular()
			throws ServletException, IOException, InvalidCrcException, IncorrectRequestException, MissingParameterException, FileUploadException,
			InterruptedException {
		TestFileSplitResult splitResult;
		final Semaphore waitForMe = new Semaphore(0);


		// begin a file upload process
		String fileId = uploadProcessor.prepareUpload(tinyFileSize, fileName);

		// get progress
		Assert.assertThat(0f, is(uploadProcessor.getProgress(fileId)));

		// upload first part
		splitResult = UploadProcessorTest.getByteArrayFromFile(tinyFile, 0, 3);
		uploadServletAsyncProcessor.process(fileId, splitResult.crc, splitResult.stream, new Listener(waitForMe));

		// wait until processing is done
		Assert.assertTrue(waitForMe.tryAcquire(5, TimeUnit.SECONDS));

		// get progress
		Assert.assertThat(Math.round(uploadProcessor.getProgress(fileId)), is(3 * 100 / tinyFileSize.intValue()));

		// upload second part
		splitResult = UploadProcessorTest.getByteArrayFromFile(tinyFile, 3, 5);
		uploadServletAsyncProcessor.process(fileId, splitResult.crc, splitResult.stream, new Listener(waitForMe));

		// wait until processing is done
		Assert.assertTrue(waitForMe.tryAcquire(5, TimeUnit.SECONDS));

		// get progress
		Assert.assertThat(Math.round(uploadProcessor.getProgress(fileId)), is(Math.round(5f / tinyFileSize.floatValue() * 100f)));

		// upload last part
		splitResult = UploadProcessorTest.getByteArrayFromFile(tinyFile, 5, tinyFileSize.intValue());
		uploadServletAsyncProcessor.process(fileId, splitResult.crc, splitResult.stream, new Listener(waitForMe));

		// wait until processing is done
		Assert.assertTrue(waitForMe.tryAcquire(5, TimeUnit.SECONDS));

		// get progress
		Assert.assertThat(Math.round(uploadProcessor.getProgress(fileId)), is(100));
	}


	// TODO need to make test for first part and second part also

	@Test
	public void testStreamDisconnection()
			throws IOException, InterruptedException, InvalidCrcException {
		processStreamDisconnection(true, false);
	}


	@Test(expected = InvalidCrcException.class)
	public void testStreamDisconnectionWithBadCrcSignature()
			throws IOException, InterruptedException, InvalidCrcException {
		processStreamDisconnection(true, true);
	}


	public void processStreamDisconnection(boolean disconnectInFirstPart, boolean failure)
			throws IOException, InterruptedException, InvalidCrcException {
		// init a file which is double the size of the slice to be able to disconnect in first or
		// second part
		long size = uploadProcessor.sliceSizeInBytes * 2;
		byte[] fileContent = new byte[(int) size];
		new Random().nextBytes(fileContent);
		byte[] fileContentClone = fileContent.clone();
		MultipartFile file = new MockMultipartFile("blob", fileContent);

		// define an input stream that emulates a stream that fails
		ByteArrayInputStream byteStream = new ByteArrayInputStream(fileContentClone) {

			int i;



			@Override
			public int read(byte[] b)
					throws IOException {
				if (i++ == 100) {
					throw new IOException("Stream ended unexpectedly");
				}
				return super.read(b);
			}


		};

		// prepare an upload
		String fileId = uploadProcessor.prepareUpload(size, fileName);

		// begin to process it
		String crcBuffered = crcHelper.getBufferedCrc(file.getInputStream()).getCrcAsString();
		final Semaphore semaphore = new Semaphore(0);
		uploadServletAsyncProcessor.process(fileId, crcBuffered, byteStream, new Listener(semaphore) {

			@Override
			public void success() {
				throw new RuntimeException();
			}


			@Override
			public void error(Exception exception) {
				if (exception.getMessage().equals("User has stopped streaming")) {
					semaphore.release();
				}
				else {
					throw new RuntimeException();
				}
			}
		});

		// and wait until error has been detected
		semaphore.tryAcquire(10, TimeUnit.MINUTES);

		// now get the state from file
		FileStateJson fileStateJson = uploadProcessor.getConfig().getPendingFiles().get(fileId);
		Long completionInBytes = fileStateJson.getFileCompletionInBytes();

		// we need to make a uncrced byte verification
		Long crcedBytes = fileStateJson.getCrcedBytes();
		Assert.assertThat(crcedBytes, lessThan(completionInBytes));

		// calculate the crc of the bytes not validated
		// if we are emulating a bad input stream, mess with it a bit:
		if (failure) {
			completionInBytes -= 10;
		}
		TestFileSplitResult emulatedFrontEndFile = UploadProcessorTest.getByteArrayFromFile(file, crcedBytes, completionInBytes - crcedBytes);
		FileUploadConfiguration fileUploadConfigurationOfFrontEndFile = new FileUploadConfiguration();
		fileUploadConfigurationOfFrontEndFile.setInputStream(emulatedFrontEndFile.stream);
		fileUploadConfigurationOfFrontEndFile.setFileId(fileId);

		// verify
		uploadProcessor.verifyCrcOfUncheckedPart(fileUploadConfigurationOfFrontEndFile);

		// and resume the stream from here
		TestFileSplitResult byteArrayFromFile = UploadProcessorTest.getByteArrayFromFile(file, completionInBytes, size);
		uploadServletAsyncProcessor.process(fileId, byteArrayFromFile.crc, byteArrayFromFile.stream, new Listener(semaphore));

		// wait for it
		semaphore.tryAcquire(10, TimeUnit.MINUTES);

		// compare the crcs
		// now calculates the crc of sent file
		String valueSource = crcHelper.getBufferedCrc(new ByteArrayInputStream(fileContent)).getCrcAsString();

		// and the one of received file
		String absoluteFullPathOfUploadedFile = staticStateManager.getEntity().getFileStates().get(fileId).getAbsoluteFullPathOfUploadedFile();
		String valueCopied = crcHelper.getBufferedCrc(new FileInputStream(new File(absoluteFullPathOfUploadedFile))).getCrcAsString();

		// assert the same
		Assert.assertThat(valueCopied, is(valueSource));

	}


	@Test
	public void testBigFileComplete()
			throws IOException, InterruptedException {

		// init a file which is about 115 MB (we want to check out-of-buffer granularity, so not an
		// exact value)
		long size = 121123456;
		byte[] fileContent = new byte[(int) size];
		new Random().nextBytes(fileContent);
		MultipartFile file = new MockMultipartFile("blob", fileContent);

		// prepare upload
		String fileId = uploadProcessor.prepareUpload(size, fileName);
		String absoluteFullPathOfUploadedFile =
				staticStateManager.getEntity().getFileStates().get(fileId).getAbsoluteFullPathOfUploadedFile();

		// set a 100mb rate big rate
		uploadProcessor.setUploadRate(fileId, 102400l);

		// for all the slices that we need to send
		long numberOfSlices = size / uploadProcessor.sliceSizeInBytes;
		for (int i = 0; i < numberOfSlices + 1; i++) {

			// prepare that slice
			long start = uploadProcessor.sliceSizeInBytes * i;
			TestFileSplitResult byteArrayFromFile = UploadProcessorTest.getByteArrayFromFile(file, start, start + uploadProcessor.sliceSizeInBytes);

			// at one point, pause it:
			if (i == numberOfSlices / 2) {

				// pause
				uploadProcessor.pauseFile(fileId);

				// get the file size
				long length = new File(absoluteFullPathOfUploadedFile).length();

				// wait a bit
				Thread.sleep(200);

				// assert the size is the same
				Assert.assertThat(new File(absoluteFullPathOfUploadedFile).length(), is(length));

				// then continue processing
				uploadProcessor.resumeFile(fileId);

			}

			// process it
			Semaphore wait = new Semaphore(0);
			uploadServletAsyncProcessor.process(fileId, byteArrayFromFile.crc, byteArrayFromFile.stream, new Listener(wait));

			// and wait for it
			Assert.assertTrue(wait.tryAcquire(1, TimeUnit.MINUTES));
		}


		// now calculates the crc of sent file
		String valueSource = crcHelper.getBufferedCrc(new ByteArrayInputStream(fileContent)).getCrcAsString();

		// and the one of received file
		String valueCopied = crcHelper.getBufferedCrc(new FileInputStream(new File(absoluteFullPathOfUploadedFile))).getCrcAsString();


		// assert the same
		Assert.assertThat(valueCopied, is(valueSource));

	}


}