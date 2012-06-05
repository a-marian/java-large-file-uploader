package com.am.jlfu.fileuploader.limiter;


import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Component;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;



@Component
@ManagedResource(objectName = "JavaLargeFileUploader:name=rateLimiterConfiguration")
public class RateLimiterConfigurationManager {

	private static final Logger log = LoggerFactory.getLogger(RateLimiterConfigurationManager.class);

	final LoadingCache<String, UploadProcessingConfiguration> requestConfigMap = CacheBuilder.newBuilder().expireAfterAccess(10, TimeUnit.MINUTES)
			.build(new CacheLoader<String, UploadProcessingConfiguration>() {

				@Override
				public UploadProcessingConfiguration load(String arg0)
						throws Exception {
					log.trace("Created new bucket for #{}", arg0);
					return new UploadProcessingConfiguration();
				}
			});


	// ///////////////
	// Configuration//
	// ///////////////

	/** The default request capacity. volatile because it can be changed. */
	// 1mb/s
	private volatile long defaultRatePerRequestInKiloBytes = 1024;

	// 1kb/s
	private volatile long minimumRatePerRequestInKiloBytes = 1;

	// 10mb/s
	private volatile long defaultRatePerClientInKiloBytes = 10 * 1024;

	// 10mb/s
	private volatile long maximumRatePerClientInKiloBytes = 10 * 1024;

	// 10mb/s
	private volatile long maximumOverAllRateInKiloBytes = 10 * 1024;



	// ///////////////


	public class UploadProcessingConfiguration {

		/**
		 * Specifies the amount of bytes that can be uploaded for an iteration of the refill process
		 * of {@link RateLimiter}
		 * */
		private long downloadAllowanceForIteration;
		private Object downloadAllowanceForIterationLock = new Object();

		/**
		 * Boolean specifying whether that request shall be cancelled (and the relating streams
		 * closed)<br>
		 * 
		 * @see RateLimiterConfigurationManager#markRequestHasShallBeCancelled(String)
		 * @see UploadProcessingConfigurationManager#requestHasToBeCancelled(String))
		 * */
		private volatile boolean cancelRequest;

		/**
		 * The desired upload rate for this request. <br>
		 * Can be null (the default rate is applied).
		 */
		private volatile Long rateInKiloBytes;

		/**
		 * Boolean specifying whether the upload is paused or not.
		 */
		private volatile boolean isPaused = false;


		/**
		 * The statistics.
		 * 
		 * @return
		 */
		private long instantRateInBytes;



		public Long getRateInKiloBytes() {
			return rateInKiloBytes;
		}


		public boolean isPaused() {
			return isPaused;
		}


		public long getDownloadAllowanceForIteration() {
			synchronized (downloadAllowanceForIterationLock) {
				return downloadAllowanceForIteration;
			}
		}


		public void setDownloadAllowanceForIteration(long downloadAllowanceForIteration) {
			synchronized (downloadAllowanceForIterationLock) {
				this.downloadAllowanceForIteration = downloadAllowanceForIteration;
			}
		}


		/**
		 * Specifies the bytes that have been read from the files.
		 * 
		 * @param bytesConsumed
		 */
		public void bytesConsumedFromAllowance(long bytesConsumed) {
			synchronized (downloadAllowanceForIterationLock) {
				downloadAllowanceForIteration -= bytesConsumed;
			}
		}


		public void setInstantRateInBytes(long instantRateInBytes) {
			this.instantRateInBytes = instantRateInBytes;
		}


		public long getInstantRateInBytes() {
			return instantRateInBytes;
		}

	}



	/**
	 * Specify that a request has to be cancelled, the file is scheduled for deletion.
	 * 
	 * @param fileId
	 * @return true if there was a pending upload for this file.
	 */
	public boolean markRequestHasShallBeCancelled(String fileId) {
		UploadProcessingConfiguration ifPresent = requestConfigMap.getIfPresent(fileId);
		if (ifPresent != null) {
			requestConfigMap.getUnchecked(fileId).cancelRequest = true;
		}
		return ifPresent != null;
	}


	public boolean requestHasToBeCancelled(String fileId) {
		return requestConfigMap.getUnchecked(fileId).cancelRequest;
	}


	public Set<Entry<String, UploadProcessingConfiguration>> getEntries() {
		return requestConfigMap.asMap().entrySet();
	}


	public void reset(String fileId) {
		final UploadProcessingConfiguration unchecked = requestConfigMap.getUnchecked(fileId);
		unchecked.cancelRequest = false;
		unchecked.isPaused = false;
	}


	public long getAllowance(String fileId) {
		return requestConfigMap.getUnchecked(fileId).getDownloadAllowanceForIteration();
	}


	public void assignRateToRequest(String fileId, Long rateInKiloBytes) {
		requestConfigMap.getUnchecked(fileId).rateInKiloBytes = rateInKiloBytes;
	}


	public Long getUploadState(String requestIdentifier) {
		return requestConfigMap.getUnchecked(requestIdentifier).instantRateInBytes;
	}


	public UploadProcessingConfiguration getUploadProcessingConfiguration(String fileId) {
		return requestConfigMap.getUnchecked(fileId);
	}


	public void pause(String fileId) {
		requestConfigMap.getUnchecked(fileId).isPaused = true;
	}


	public void resume(String fileId) {
		requestConfigMap.getUnchecked(fileId).isPaused = false;
	}


	public long getDefaultRatePerRequestInKiloBytes() {
		return defaultRatePerRequestInKiloBytes;
	}


	public void setDefaultRatePerRequestInKiloBytes(long defaultRatePerRequestInKiloBytes) {
		this.defaultRatePerRequestInKiloBytes = defaultRatePerRequestInKiloBytes;
	}


	public long getMinimumRatePerRequestInKiloBytes() {
		return minimumRatePerRequestInKiloBytes;
	}


	public void setMinimumRatePerRequestInKiloBytes(long minimumRatePerRequestInKiloBytes) {
		this.minimumRatePerRequestInKiloBytes = minimumRatePerRequestInKiloBytes;
	}


	public long getDefaultRatePerClientInKiloBytes() {
		return defaultRatePerClientInKiloBytes;
	}


	public void setDefaultRatePerClientInKiloBytes(long defaultRatePerClientInKiloBytes) {
		this.defaultRatePerClientInKiloBytes = defaultRatePerClientInKiloBytes;
	}


	public long getMaximumRatePerClientInKiloBytes() {
		return maximumRatePerClientInKiloBytes;
	}


	public void setMaximumRatePerClientInKiloBytes(long maximumRatePerClientInKiloBytes) {
		this.maximumRatePerClientInKiloBytes = maximumRatePerClientInKiloBytes;
	}


	public long getMaximumOverAllRateInKiloBytes() {
		return maximumOverAllRateInKiloBytes;
	}


	public void setMaximumOverAllRateInKiloBytes(long maximumOverAllRateInKiloBytes) {
		this.maximumOverAllRateInKiloBytes = maximumOverAllRateInKiloBytes;
	}


}