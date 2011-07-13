package com.netflix.astyanax.connectionpool.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.cliffc.high_scale_lib.NonBlockingHashMap;

import com.netflix.astyanax.connectionpool.BadHostDetector;
import com.netflix.astyanax.connectionpool.Connection;
import com.netflix.astyanax.connectionpool.ConnectionFactory;
import com.netflix.astyanax.connectionpool.ConnectionPool;
import com.netflix.astyanax.connectionpool.ConnectionPoolConfiguration;
import com.netflix.astyanax.connectionpool.ConnectionPoolMonitor;
import com.netflix.astyanax.connectionpool.ExhaustedStrategy;
import com.netflix.astyanax.connectionpool.FailoverStrategy;
import com.netflix.astyanax.connectionpool.Host;
import com.netflix.astyanax.connectionpool.HostConnectionPool;
import com.netflix.astyanax.connectionpool.HostRetryService;
import com.netflix.astyanax.connectionpool.HostRetryService.ReconnectCallback;

public abstract class AbstractHostPartitionConnectionPool<CL> implements ConnectionPool<CL>{
	protected final NonBlockingHashMap<Host, HostConnectionPool<CL>> foundHosts;
	protected final NonBlockingHashMap<Host, HostConnectionPool<CL>> activeHosts;
	protected final ConnectionPoolConfiguration config;
	protected final ConnectionFactory<CL> factory;
	protected final ConnectionPoolMonitor monitor;
	protected final HostRetryService retryService;
	protected final BadHostDetector badHostDetector;
	protected final FailoverStrategy failoverStrategy;
	protected final ExhaustedStrategy exhaustedStrategy;

	public AbstractHostPartitionConnectionPool(ConnectionPoolConfiguration config, ConnectionFactory<CL> factory) {
		this.foundHosts = new NonBlockingHashMap<Host, HostConnectionPool<CL>>();
		this.activeHosts = new NonBlockingHashMap<Host, HostConnectionPool<CL>>();
		this.config = config;
		this.factory = factory;
		this.retryService = new ThreadedRetryService<CL>(config.getRetryBackoffStrategy(), factory);
		this.exhaustedStrategy = config.getExhaustedStrategyFactory().createInstance(this.config);
		this.failoverStrategy = config.getFailoverStrategyFactory().createInstance(this.config);
		this.monitor = config.getConnectionPoolMonitor();
		this.badHostDetector = config.getBadHostDetector();
	}
	
	@Override
	public void start() {
		for (Host host : config.getSeedHosts()) {
			addHost(host);
		}
	}
	
	@Override
	public void returnConnection(Connection<CL> connection) {
		connection.getHostConnectionPool().returnConnection(connection);
		this.monitor.incConnectionReturned(connection.getHostConnectionPool().getHost());
		
		if (!connection.isOpen()) {
			if (null != connection.getLastException() &&
				badHostDetector.checkFailure(connection.getHostConnectionPool().getHost(), 
						connection.getLastException())) {
				this.markHostAsDown(connection.getHostConnectionPool(), connection.getLastException());
			}
		}
	}
	
	@Override
	public final void addHost(Host host) {
		HostConnectionPool<CL> pool = new SimpleHostConnectionPool<CL>(host, factory, config.getMaxConnsPerHost());
		if (null == this.foundHosts.putIfAbsent(host, pool)) {
			this.monitor.onHostAdded(host, new ImmutableHostConnectionPool<CL>(pool));
			this.activeHosts.putIfAbsent(host, pool);
			this.onHostUp(pool);
		}
	}

	protected void onHostUp(HostConnectionPool<CL> pool) {
	}

	@Override
	public final void removeHost(Host host) {
		HostConnectionPool<CL> pool = foundHosts.remove(host);
		if (pool != null) {
			this.monitor.onHostRemoved(host);
			pool.shutdown();
			onHostDown(pool);
		}
		this.activeHosts.remove(host);
	}
	
	protected void onHostDown(HostConnectionPool<CL> pool) {
	}

	protected void markHostAsDown(HostConnectionPool<CL> pool, Exception reason) {
		if (this.activeHosts.remove(pool.getHost(), pool)) {
			this.monitor.onHostDown(pool.getHost(), reason);
			pool.shutdown();
			onHostDown(pool);
			
			this.retryService.addHost(pool.getHost(), new ReconnectCallback() {
				@Override
				public void onReconnected(Host host) {
					reactivateHost(host);
				}
			});
		}
	}
	
	private void reactivateHost(Host host) {
		HostConnectionPool<CL> pool = new SimpleHostConnectionPool<CL>(host, factory, config.getMaxConnsPerHost());
		this.monitor.onHostReactivated(host, new ImmutableHostConnectionPool<CL>(pool));
		
		foundHosts.put(host, pool);
		activeHosts.put(host, pool);
		
		onHostUp(pool);
	}
	
	@Override
	public void setHosts(Map<String, List<Host>> ring) {
		// Temporary list of hosts to remove.  Any host not in the new ring
		// will be removed
		Set<Host> hostsToRemove = new HashSet<Host>();
		for (Entry<Host, HostConnectionPool<CL>> h : foundHosts.entrySet()) {
			hostsToRemove.add(h.getKey());
		}
		
		// Add new hosts.  
		for (Map.Entry<String, List<Host>> entry : ring.entrySet()) {
			List<Host> hosts = entry.getValue();
			if (hosts != null) {
				for (Host host : hosts) {
					HostConnectionPool<CL> pool = new SimpleHostConnectionPool<CL>(host, factory, config.getMaxConnsPerHost());
					if (null == foundHosts.putIfAbsent(host, pool)) {
						this.monitor.onHostAdded(host, new ImmutableHostConnectionPool<CL>(pool));
						activeHosts.put(host, pool);
					}
					hostsToRemove.remove(host);
				}
			}
		}
		
		// Remove any hosts that are no longer in the ring
		for (Host host : hostsToRemove) {
			removeHost(host);
		}
	}

	@Override
	public void shutdown() {
		this.retryService.shutdown();
		for (Entry<Host, HostConnectionPool<CL>> pool : foundHosts.entrySet()) {
			pool.getValue().shutdown();
		}
	}
}