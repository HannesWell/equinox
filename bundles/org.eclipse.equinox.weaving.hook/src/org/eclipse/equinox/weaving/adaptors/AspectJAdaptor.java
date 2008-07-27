/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *   David Knibb               initial implementation      
 *   Matthew Webster           Eclipse 3.2 changes
 *   Martin Lippert            minor changes and bugfixes     
 *******************************************************************************/
package org.eclipse.equinox.weaving.adaptors;

import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.equinox.service.weaving.CacheEntry;
import org.eclipse.equinox.service.weaving.ICachingService;
import org.eclipse.equinox.service.weaving.IWeavingService;
import org.eclipse.equinox.weaving.hooks.AspectJBundleFile;
import org.eclipse.osgi.baseadaptor.BaseData;
import org.eclipse.osgi.baseadaptor.bundlefile.BundleFile;
import org.eclipse.osgi.baseadaptor.loader.BaseClassLoader;
import org.eclipse.osgi.framework.internal.core.BundleFragment;
import org.eclipse.osgi.framework.internal.core.BundleHost;
import org.osgi.framework.Bundle;

public class AspectJAdaptor implements IAspectJAdaptor {

	private boolean initialized = false;
	private BaseData data;
	private AspectJAdaptorFactory factory;
	private BaseClassLoader baseLoader;
	private Bundle bundle;
	private String symbolicName;
	private IWeavingService weavingService;
	private ICachingService cachingService;
	
	private static ThreadLocalSet identifyRecursionSet = new ThreadLocalSet();
	
	private static class ThreadLocalSet extends ThreadLocal {
		
		public void put(Object obj) {
			Set set = (Set) get();
			if (set.contains(obj)) {
				throw new RuntimeException(obj.toString());
			}
			set.add(obj);
		}
		
		public void remove(Object obj) {
			Set set = (Set) get();
			if (!set.contains(obj)) {
				throw new RuntimeException(obj.toString());
			}
			set.remove(obj);
		}
		
		public boolean contains(Object obj) {
			Set set = (Set) get();
			return set.contains(obj);
		}
		
		protected Object initialValue() {
			return new HashSet();
		}
	}
	
	public AspectJAdaptor (BaseData baseData, AspectJAdaptorFactory serviceFactory, BaseClassLoader baseClassLoader, IWeavingService weavingService, ICachingService cachingService) {
		this.data = baseData;
		this.factory = serviceFactory;
		this.symbolicName = baseData.getLocation();
		if (Debug.DEBUG_GENERAL) Debug.println("- AspectJAdaptor.AspectJAdaptor() bundle=" + symbolicName);
	}

	private void initialize () {
		synchronized(this) {
			if (initialized) return;
			
			this.bundle = data.getBundle();
			this.symbolicName = data.getSymbolicName();
			if (!identifyRecursionSet.contains(this)) {
				identifyRecursionSet.put(this);
				
				if (Debug.DEBUG_GENERAL) Debug.println("> AspectJAdaptor.initialize() bundle=" + symbolicName + ", baseLoader=" + baseLoader);
				
				if (symbolicName.startsWith("org.aspectj")) {
					if (Debug.DEBUG_GENERAL) Debug.println("- AspectJAdaptor.initialize() symbolicName=" + symbolicName + ", baseLoader=" + baseLoader);
				}
				else if (baseLoader != null) {
					weavingService = factory.getWeavingService(baseLoader);
					cachingService = factory.getCachingService(baseLoader,bundle,weavingService);
				}
				else if (bundle instanceof BundleFragment) {
					BundleFragment fragment = (BundleFragment)bundle;
					BundleHost host = (BundleHost)factory.getHost(fragment);
					if (Debug.DEBUG_GENERAL) Debug.println("- AspectJAdaptor.initialize() symbolicName=" + symbolicName + ", host=" + host);
	
					BaseData hostData = (BaseData)host.getBundleData();
	//				System.err.println("? AspectJAdaptor.initialize() bundleData=" + hostData);
					BundleFile bundleFile = hostData.getBundleFile();
					if (bundleFile instanceof AspectJBundleFile) {
						AspectJBundleFile hostFile = (AspectJBundleFile)bundleFile;
	//					System.err.println("? AspectJAdaptor.initialize() bundleFile=" + hostFile);
						AspectJAdaptor hostAdaptor = (AspectJAdaptor)hostFile.getAdaptor();
	//					System.err.println("? AspectJAdaptor.initialize() bundleFile=" + hostAdaptor);
						weavingService = hostAdaptor.weavingService;
						cachingService = factory.getCachingService(hostAdaptor.baseLoader,bundle,weavingService);
					}
				}
				else {
					if (Debug.DEBUG_GENERAL) Debug.println("W AspectJAdaptor.initialize() symbolicName=" + symbolicName + ", baseLoader=" + baseLoader);
				}
				initialized = true;
				identifyRecursionSet.remove(this);
			}
			
			if (Debug.DEBUG_GENERAL) Debug.println("< AspectJAdaptor.initialize() weavingService=" + (weavingService != null) + ", cachingService=" + (cachingService != null)); 
		}
	}

	public void setBaseClassLoader (BaseClassLoader baseClassLoader) {
		this.baseLoader = baseClassLoader;

		if (Debug.DEBUG_GENERAL) Debug.println("- AspectJAdaptor.setBaseClassLoader() bundle=" + symbolicName + ", baseLoader=" + baseLoader);
	}
	
	public CacheEntry findClass (String name, URL sourceFileURL) {
		if (Debug.DEBUG_CACHE) Debug.println("> AspectJAdaptor.findClass() bundle=" + symbolicName + ", url=" + sourceFileURL + ", name=" + name);
		CacheEntry cacheEntry = null;

		initialize();
		if (cachingService != null) {
			cacheEntry = cachingService.findStoredClass("",sourceFileURL,name);
		}

		if (Debug.DEBUG_CACHE) Debug.println("< AspectJAdaptor.findClass() cacheEntry=" + cacheEntry);
		return cacheEntry;
	}
	
	public byte[] weaveClass (String name, byte[] bytes) {
		if (Debug.DEBUG_WEAVE) Debug.println("> AspectJAdaptor.weaveClass() bundle=" + symbolicName + ", name=" + name + ", bytes=" + bytes.length);
		byte[] newBytes = null;

		initialize();
		if (/*shouldWeave(bytes) && */ weavingService != null){
			try {
				newBytes = weavingService.preProcess(name,bytes,(ClassLoader)baseLoader);
			}
			catch (IOException ex) {
				throw new ClassFormatError(ex.toString());
			}
		}
		
		if (Debug.DEBUG_WEAVE) Debug.println("< AspectJAdaptor.weaveClass() newBytes=" + newBytes);
		return newBytes;
	}

	public boolean storeClass (String name, URL sourceFileURL, Class clazz, byte[] classbytes) {
		if (Debug.DEBUG_CACHE) Debug.println("> AspectJAdaptor.storeClass() bundle=" + symbolicName + ", url=" + sourceFileURL + ", name=" + name + ", clazz=" + clazz);
		boolean stored = false;

		initialize();
		if (cachingService != null) {
			//have we generated a closure? 
			//If so we cannot store in shared cache (as closure will be lost for future runs)
			if (weavingService != null && weavingService.generatedClassesExistFor((ClassLoader)baseLoader,name)) {
				weavingService.flushGeneratedClasses((ClassLoader)baseLoader);
				if (Debug.DEBUG_CACHE) Debug.println("- AspectJAdaptor.storeClass() generatedClassesExistFor=true");
//				return clazz;
			}
			else{
				stored = cachingService.storeClass("",sourceFileURL,clazz,classbytes);
				if(!stored){
					if (Debug.DEBUG_CACHE) Debug.println("E AspectJHook.storeClass() bundle=" + symbolicName + ", name=" + name);
				}
			}
		}
		if (Debug.DEBUG_CACHE) Debug.println("< AspectJAdaptor.storeClass() stored=" + stored);
		return stored;
	}

	public String toString () {
		return "AspectJAdaptor[" + symbolicName + "]";
	}

}