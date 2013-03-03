package com.kedzie.vbox.soap;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.SoapFault;
import org.ksoap2.serialization.KvmSerializable;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapPrimitive;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import org.xmlpull.v1.XmlPullParserException;

import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import com.google.common.base.Objects;
import com.kedzie.vbox.BuildConfig;
import com.kedzie.vbox.api.IDHCPServer;
import com.kedzie.vbox.api.IDisplay;
import com.kedzie.vbox.api.IEvent;
import com.kedzie.vbox.api.IHost;
import com.kedzie.vbox.api.IHostNetworkInterface;
import com.kedzie.vbox.api.IMachine;
import com.kedzie.vbox.api.IMachineStateChangedEvent;
import com.kedzie.vbox.api.IManagedObjectRef;
import com.kedzie.vbox.api.IMedium;
import com.kedzie.vbox.api.INetworkAdapter;
import com.kedzie.vbox.api.IProgress;
import com.kedzie.vbox.api.ISession;
import com.kedzie.vbox.api.ISessionStateChangedEvent;
import com.kedzie.vbox.api.ISnapshotDeletedEvent;
import com.kedzie.vbox.api.ISnapshotTakenEvent;
import com.kedzie.vbox.api.IVirtualBox;
import com.kedzie.vbox.api.Screenshot;
import com.kedzie.vbox.api.jaxb.LockType;
import com.kedzie.vbox.api.jaxb.MachineState;
import com.kedzie.vbox.api.jaxb.VBoxEventType;
import com.kedzie.vbox.app.Tuple;
import com.kedzie.vbox.app.Utils;
import com.kedzie.vbox.metrics.MetricQuery;
import com.kedzie.vbox.server.Server;
import com.kedzie.vbox.soap.ssl.InteractiveTrustedHttpsTransport;
import com.kedzie.vbox.soap.ssl.KeystoreTrustedHttpsTransport;

/**
 * VirtualBox JAX-WS API
 * @apiviz.landmark
 * @apiviz.stereotype service
 * @apiviz.owns com.kedzie.vbox.api.IVirtualBox
 * @apiviz.owns com.kedzie.vbox.soap.HttpTransport
 * @apiviz.owns com.kedzie.vbox.soap.TrustedHttpsTransport
 * @apiviz.owns com.kedzie.vbox.server.Server
 * @apiviz.uses com.kedzie.vbox.soap.KSOAP
 * @apiviz.composedOf com.kedzie.vbox.soap.VBoxSvc$KSOAPInvocationHandler
 */
public class VBoxSvc implements Parcelable, Externalizable {
	private static final String TAG = "VBoxSvc";
	protected static final int TIMEOUT = 20000;
	public static final String BUNDLE = "vmgr", NAMESPACE = "http://www.virtualbox.org/";
	private static final ClassLoader LOADER = VBoxSvc.class.getClassLoader();
	
	public static final Parcelable.Creator<VBoxSvc> CREATOR = new Parcelable.Creator<VBoxSvc>() {
		public VBoxSvc createFromParcel(Parcel in) {
			VBoxSvc svc = new VBoxSvc((Server)in.readParcelable(LOADER));
			svc._vbox = svc.getProxy(IVirtualBox.class, in.readString());
			svc.init();
			return svc;
		}
		public VBoxSvc[] newArray(int size) {
			return new VBoxSvc[size];
		}
	};
	
	/**
	 * Asynchronous return value handler
	 */
	public static interface FutureValue {
		
		/**
		 * Handle asynchronous method return value
		 * @param obj		the return value
		 */
		public void handleValue(Object obj);
	}
	
	/**
	 * Thread for making SOAP invocations
	 */
	public class AsynchronousThread extends Thread {
		private String name;
		private SerializationEnvelope envelope;
		private FutureValue future;
		private Class<?> returnType;
		private Type genericType;
		private String cacheKey;
		private Map<String, Object> _cache;
		private KSOAP methodKSOAP;
		
		public AsynchronousThread(String name, SerializationEnvelope envelope) {
			this.name=name;
			this.envelope = envelope;
		}
		
		public AsynchronousThread(String name, SerializationEnvelope envelope, FutureValue future, Class<?> returnType, Type genericType, String cacheKey, Map<String, Object> cache, KSOAP ksoap) {
			this.name=name;
			this.envelope = envelope;
			this.future=future;
			this.returnType=returnType;
			this.genericType=genericType;
			this.cacheKey=cacheKey;
			this._cache=cache;
			this.methodKSOAP = ksoap;
		}
		
		@Override
		public void run() {
			try {
				_transport.call(name, envelope);
				if(future!=null) {
					Object ret = envelope.getResponse(returnType, genericType);
					if(methodKSOAP!=null && methodKSOAP.cacheable()) 
						_cache.put(cacheKey, ret);
					future.handleValue(ret);
				}
			} catch (Exception e) {
				Log.e(TAG, "Error invoking asynchronous method", e);
			}
		}
	}
	
	/**
	 * Make remote calls to VBox JAXWS API based on method metadata from {@link KSOAP} annotations.
	 */
	public class KSOAPInvocationHandler implements InvocationHandler, Serializable {
		private static final long serialVersionUID = 1L;
		
		/** managed object UIUD */
		private String _uiud;
		
		/** type of {@link IManagedObjectRef} */
		private Class<?> _type;
		
		/** cached property values */
		private Map<String, Object> _cache;

		public KSOAPInvocationHandler(String id, Class<?> type, Map<String,Object> cache) {
			_uiud=id;
			_type=type;
			_cache = cache!=null ? cache : new HashMap<String, Object>();
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args)throws Throwable {
			synchronized(VBoxSvc.this) {
				String methodName = method.getName();
				boolean isPopulateView = methodName.equals("populateView");
				FutureValue future = null;
				if(method.getName().equals("hashCode")) return Objects.hashCode(_uiud);
				if(method.getName().equals("toString")) {
					return _type.getSimpleName() + " #" + _uiud + Utils.toString("Cache", _cache);
				}
				if(method.getName().equals("equals")) {
					if(args[0]==null) return false;
					if(!(args[0] instanceof IManagedObjectRef) || !_type.isAssignableFrom(args[0].getClass())) 
						return false;
					return Objects.equal(_uiud, ((IManagedObjectRef)args[0]).getIdRef());
				}
				if(method.getName().equals("clearCache")) { 
						_cache.clear(); 
						return null; 
				}
				if(method.getName().equals("clearCacheNamed")) { 
					for(String name : (String[])args[0]) 
						_cache.remove(name); 
					return null;
				}
				if(method.getName().equals("getAPI")) return VBoxSvc.this;
				if(method.getName().equals("describeContents")) return 0;
				if(method.getName().equals("getCache")) return _cache;
				if(method.getName().equals("getIdRef")) return _uiud;
				if(method.getName().equals("writeToParcel")) {
					Parcel out = (Parcel)args[0];
					out.writeParcelable(VBoxSvc.this, 0);
					out.writeString(_uiud);
					out.writeMap(_cache);
					return null;
				}
				if(isPopulateView) {
					methodName = (String)args[0];
					future = (FutureValue)args[1];
				}
				KSOAP ksoap = method.getAnnotation(KSOAP.class);
				if(ksoap==null)
					ksoap=method.getDeclaringClass().getAnnotation(KSOAP.class);
				
				String cacheKey = method.getName();
				if(ksoap.cacheable()) {
					if(args!=null) {
						for(Object arg : args)
							cacheKey+="-"+arg.toString();
					}
					if(_cache.containsKey(cacheKey)) {
						if(isPopulateView) {
							future.handleValue(_cache.get(cacheKey));
							return null;
						} else
							return _cache.get(cacheKey);
					}
				}
				SoapObject request = new SoapObject(NAMESPACE, (Utils.isEmpty(ksoap.prefix()) ? _type.getSimpleName() : ksoap.prefix())+"_"+method.getName());
				if (!Utils.isEmpty(ksoap.thisReference()))
					request.addProperty(ksoap.thisReference(), _uiud);
				if(args!=null) {
					for(int i=0; i<args.length; i++)
						marshal(request, Utils.getAnnotation(KSOAP.class, method.getParameterAnnotations()[i]),  method.getParameterTypes()[i],	method.getGenericParameterTypes()[i],	args[i]);
				}
				SerializationEnvelope envelope = new SerializationEnvelope();
				envelope.setOutputSoapObject(request);
				envelope.setAddAdornments(false);
				
				if(BuildConfig.DEBUG) Log.v(TAG, "Remote call: " + request.getName());
				if(method.isAnnotationPresent(Asyncronous.class)) {
					new AsynchronousThread(NAMESPACE+request.getName(), envelope).start();
					return null;
				} else if(isPopulateView) {
					new AsynchronousThread(NAMESPACE+request.getName(), envelope, future, method.getReturnType(), method.getGenericReturnType(), cacheKey, _cache, ksoap).start();
					return null;
				} else {
					_transport.call(NAMESPACE+request.getName(), envelope);
					Object ret = envelope.getResponse(method.getReturnType(), method.getGenericReturnType());
					if(ksoap.cacheable()) 
						_cache.put(cacheKey, ret);
					//update cache value of property if we are calling a setter
					if(methodName.startsWith("set")) {
						String getterName = "get"+methodName.substring(3);
						_cache.put(getterName, ret);
					}
					return ret;
				}
			}
		}
		
		/**
		 * Add an argument to a SOAP request
		 * @param request  SOAP request
		 * @param ksoap   parameter annotation with marshalling configuration
		 * @param clazz     {@link Class} of parameter
		 * @param gType   Generic type of parameter
		 * @param obj  object to marshall
		 */
		private void marshal(SoapObject request, KSOAP ksoap, Class<?> clazz, Type gType, Object obj) {
			if(obj==null) return;
			if(clazz.isArray()) { //Arrays
				for(Object o : (Object[])obj)  
					marshal( request, ksoap, clazz.getComponentType(), gType,  o );
			} else if(Collection.class.isAssignableFrom(clazz)) { //Collections
				Class<?> pClazz = Utils.getTypeParameter(gType,0);
				for(Object o : (List<?>)obj) 
					marshal(request, ksoap, pClazz, gType,  o );
			} else if(!Utils.isEmpty(ksoap.type())) //if annotation specifies SOAP datatype, i.e. unsignedint
				request.addProperty( ksoap.value(), new SoapPrimitive(ksoap.namespace(), ksoap.type(), obj.toString()));
			else if(IManagedObjectRef.class.isAssignableFrom(clazz))
				request.addProperty(ksoap.value(),  ((IManagedObjectRef)obj).getIdRef() );
			else if(clazz.isEnum())
				request.addProperty(ksoap.value(),  new SoapPrimitive(NAMESPACE, clazz.getSimpleName(), obj.toString() ));
			else {
				for(Marshaller m : _marshallers) {
					if(m.handleObject(clazz)) {
						m.marshal(request, ksoap, clazz, gType, obj);
						return;
					}
				}
				request.addProperty(ksoap.value(), obj);
			}
		}
	}

	/**
	 * Handles unmarshalling of SOAP response based on {@link KSOAP} annotation metadata
	 */
	class SerializationEnvelope extends SoapSerializationEnvelope {

		/** Reflection cache.  Maps from [classname].[property] to [setter-method] */
		private Map<String, Method> typeCache = new HashMap<String, Method>();
		
		public SerializationEnvelope() {
			super(SoapEnvelope.VER11);
		}

		/**
		 * Unmarshall SoapEnvelope to correct type
		 * @param returnType   type to umarshall
		 * @param genericType  parameterized type
		 * @return  unmarshalled return value
		 * @throws SoapFault
		 */
		public Object getResponse(Class<?> returnType, Type genericType) throws SoapFault {
			if (bodyIn instanceof SoapFault) throw (SoapFault) bodyIn;
			boolean IS_COLLECTION = Collection.class.isAssignableFrom(returnType);
			boolean IS_MAP = Map.class.isAssignableFrom(returnType);
			boolean IS_ARRAY = returnType.isArray() && !returnType.getComponentType().equals(byte.class);
			KvmSerializable ks = (KvmSerializable) bodyIn;
			if ((ks.getPropertyCount()==0 && !IS_COLLECTION && !IS_MAP)) {
				Log.w(TAG, "returning NULL because property count is 0");
				return null;
			}
			if(IS_MAP) {
			    Type valueType = ((ParameterizedType)genericType).getActualTypeArguments()[1];
			    if(!(valueType instanceof Class)) {  //Map<String, List<String>>
			        Map<String, List<String>> map = new HashMap<String, List<String>>();
	                PropertyInfo info = new PropertyInfo();
	                for (int i = 0; i < ks.getPropertyCount(); i++) {
	                    ks.getPropertyInfo(i, null, info);
	                    if (!map.containsKey(info.getName()))
	                        map.put(info.getName(), new ArrayList<String>());
	                    map.get(info.getName()).add(   ks.getProperty(i).toString() );
	                }
	                return map;
			    } else {		//Map<String,String>
			        Map<String, String> map = new HashMap<String, String>();
			        PropertyInfo info = new PropertyInfo();
                    for (int i = 0; i < ks.getPropertyCount(); i++) {
                        ks.getPropertyInfo(i, null, info);
                        map.put(info.getName(), ks.getProperty(i).toString());
                    }
                    return map;
			    }
			} else if(IS_COLLECTION) {
				Class<?> pClazz = Utils.getTypeParameter(genericType,0);
				Collection<Object> list = new ArrayList<Object>(ks.getPropertyCount());
				for (int i = 0; i < ks.getPropertyCount(); i++)
					list.add( unmarshal(pClazz, genericType, ks.getProperty(i)) );
				return list;
			} else if(IS_ARRAY) {
				Class<?> pClazz = returnType.getComponentType();
				Object[] array = (Object[])Array.newInstance(pClazz, ks.getPropertyCount());
				for (int i = 0; i < ks.getPropertyCount(); i++)
					array[i] = unmarshal(pClazz, genericType, ks.getProperty(i));
				return array;
			}
			return unmarshal(returnType, genericType, ks.getProperty(0));
		}

		/**
		 * convert string return value to correct type
		 * @param returnType remote method return type
		 * @param genericType remote method return type (parameterized)
		 * @param ret  marshalled value
		 * @return unmarshalled return value
		 */
		private Object unmarshal(Class<?> returnType, Type genericType, Object ret) {
			if(ret==null || ret.toString().equals("anyType{}")) return null;
			if(returnType.isArray() && returnType.getComponentType().equals(byte.class)) {
				return android.util.Base64.decode(ret.toString().getBytes(), android.util.Base64.DEFAULT);
			} else if(returnType.equals(Boolean.class) || returnType.equals(boolean.class)) {
				return Boolean.valueOf(ret.toString());
			} else if(returnType.equals(Integer.class) || returnType.equals(int.class)) {
			        return Integer.valueOf(ret.toString());
			} else if(returnType.equals(Long.class) || returnType.equals(long.class)) {
                    return Long.valueOf(ret.toString());
			} else if(returnType.equals(String.class))
				return ret.toString();
			else if(IManagedObjectRef.class.isAssignableFrom(returnType))
				return getProxy(returnType, ret.toString());
			else if(returnType.isEnum()) {
				for( Object element : returnType.getEnumConstants())
					if( element.toString().equals( ret.toString() ) )
						return element;
			} else if(returnType.isAnnotationPresent(KSoapObject.class)) {
			    try {
			        if(BuildConfig.DEBUG) Log.v(TAG, "Unmarshalling Complex Object: " + returnType.getName());
                    Object pojo = returnType.newInstance();
                    SoapObject soapObject = (SoapObject)ret;
                    PropertyInfo propertyInfo = new PropertyInfo();
                    for(int i=0; i<soapObject.getPropertyCount(); i++) {
                        soapObject.getPropertyInfo(i, propertyInfo);
                        Method setterMethod = findSetterMethod(returnType, propertyInfo.getName());
                        if(setterMethod==null) continue;
                        Class<?> propertyType = setterMethod.getParameterTypes()[0];
                        Object value = unmarshal(propertyType, propertyType, propertyInfo.getValue());
                       	if(BuildConfig.DEBUG) Log.v(TAG, String.format("Setting property: %1$s.%2$s = %3$s", returnType.getSimpleName(), propertyInfo.getName(), value));
                       	setterMethod.invoke(pojo, value);
                    }
                    return pojo;
                } catch (Exception e) {
                    Log.e(TAG, "Error unmarshalling complex object: " + returnType.getName(), e);
                } 
			}
			for(Marshaller m : _marshallers) {
				if(m.handleObject(returnType))
					return m.unmarshal(returnType, genericType, ret);
			}
			return ret;
		}
		
		/**
		 * Find and cache the setter method for a particular property
		 * @param clazz			object type
		 * @param property	property name
		 * @return	the setter method
		 */
		private Method findSetterMethod(Class<?> clazz, String property) {
			String key = clazz.getSimpleName()+"."+property;
			if(typeCache.containsKey(key)) 
				return typeCache.get(key);
			String setterMethodName = "set"+property.substring(0, 1).toUpperCase()+property.substring(1);
			for(Method method : clazz.getMethods()) 
				if(method.getName().equals(setterMethodName)) {
					typeCache.put(key, method);
					return method;
				}
			Log.w(TAG, "No Setter Found: " + setterMethodName);
			return null;
		}
	}

	protected Server _server;
	protected IVirtualBox _vbox;
	protected HttpTransportSE  _transport;
	protected List<Marshaller> _marshallers = new ArrayList<Marshaller>();
	
	/**
	 * @param server	VirtualBox webservice server
	 */
	public VBoxSvc(Server server) {
		_server=server;
		_transport = server.isSSL() ? new KeystoreTrustedHttpsTransport(server, TIMEOUT) : 
					new HttpTransport("http://"+server.getHost() + ":" + server.getPort(), TIMEOUT);
		init();
	}

	/**
	 * Copy constructor
	 * @param copy	The original {@link VBoxSvc} to copy
	 */
	public VBoxSvc(VBoxSvc copy) {
		this(copy._server);
		_vbox = getProxy(IVirtualBox.class, copy._vbox.getIdRef());
		init();
	}
	
	private void init() {
	}
	
	public IVirtualBox getVBox() {
		return _vbox;
	}
	
	public void setVBox(IVirtualBox box) {
	    _vbox=box;
	}
	
	public Server getServer() {
		return _server;
	}
	
	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeParcelable(_server, 0);
		dest.writeString(_vbox.getIdRef());
	}
	
	@Override
	public void readExternal(ObjectInput input) throws IOException, ClassNotFoundException {
		_server=(Server)input.readObject();
		_transport = _server.isSSL() ? new KeystoreTrustedHttpsTransport(_server, TIMEOUT) : 
					new HttpTransport("http://"+_server.getHost() + ":" + _server.getPort(), TIMEOUT);
		_vbox = getProxy(IVirtualBox.class, input.readUTF());
		init();
	}

	@Override
	public void writeExternal(ObjectOutput output) throws IOException {
		Log.d(TAG, "Serializing");
		output.writeObject(_server);
		output.writeUTF(_vbox.getIdRef());
	}

	/**
	 * Create remote-invocation proxy w/o cached properties
	 * @param clazz 		type of {@link IManagedObjectRef}
	 * @param id			UIUD of {@link IManagedObjectRef}
	 * @return 				remote invocation proxy
	 */
	public <T> T getProxy(Class<T> clazz, String id) {
		return getProxy(clazz, id, null);
	}

	/**
	 * Create remote-invocation proxy w/cached properties
	 * @param clazz 		type of {@link IManagedObjectRef}
	 * @param id 			UIUD of {@link IManagedObjectRef}
	 * @param 				cached properties
	 * @return 				remote invocation proxy
	 */
	public <T> T getProxy(Class<T> clazz, String id, Map<String, Object> cache) {
		T proxy = clazz.cast( Proxy.newProxyInstance(LOADER, new Class [] { clazz }, new KSOAPInvocationHandler(id, clazz, cache)));
		if(IEvent.class.equals(clazz)) {
			VBoxEventType type = ((IEvent)proxy).getType();
			if(type.equals(VBoxEventType.ON_MACHINE_STATE_CHANGED))
				return clazz.cast(getProxy( IMachineStateChangedEvent.class, id, cache ));
			else if(type.equals(VBoxEventType.ON_SESSION_STATE_CHANGED))
				return clazz.cast(getProxy( ISessionStateChangedEvent.class, id, cache ));
			else if(type.equals(VBoxEventType.ON_SNAPSHOT_DELETED))
                return clazz.cast(getProxy( ISnapshotDeletedEvent.class, id, cache ));
			else if(type.equals(VBoxEventType.ON_SNAPSHOT_TAKEN))
                return clazz.cast(getProxy( ISnapshotTakenEvent.class, id, cache ));
		}
		return proxy;
	}

	/**
	 * Connect to <code>vboxwebsrv</code> & initialize the VBoxSvc API interface
	 * @param username username
	 * @param password password
	 * @return initialized {@link IVirtualBox} API interface
	 * @throws IOException
	 * @throws XmlPullParserException
	 */
	public IVirtualBox logon() throws IOException  {
		try {
			return (_vbox = getProxy(IVirtualBox.class, null).logon(_server.getUsername(), _server.getPassword()));
		} catch(SoapFault e) {
			Log.e(TAG, "Logon error", e);
			throw new ConnectException("Authentication Error");
		}
	}
	
	/**
	 * Logoff from VirtualBox API
	 * @throws IOException 
	 */
	public void logoff() throws IOException {
		if(_vbox!=null)
			_vbox.logoff();
		_vbox=null;
	}
	
	/**
	 * Query metric data for specified {@link IManagedObjectRef}
	 * @param object object to get metrics for
	 * @param metrics specify which metrics/accumulations to query. * for all
	 * @return  {@link Map} from metric name to {@link MetricQuery}
	 * @throws IOException
	 */
	public Map<String, MetricQuery> queryMetrics(String object, String...metrics) throws IOException {
		Map<String, List<String>> data= _vbox.getPerformanceCollector().queryMetricsData(metrics, new String[] { object });
		
		Map<String, MetricQuery> ret = new HashMap<String, MetricQuery>();
		for(int i=0; i<data.get("returnMetricNames").size(); i++) {
			MetricQuery q = new MetricQuery();
			q.name=(String)data.get("returnMetricNames").get(i);
			q.object=(String)data.get("returnObjects").get(i);
			q.scale=Integer.valueOf(data.get("returnScales").get(i));
			q.unit=(String)data.get("returnUnits").get(i);
			int start = Integer.valueOf( data.get("returnDataIndices").get(i));
			int length = Integer.valueOf( data.get("returnDataLengths").get(i));
			
			q.values= new int[length];
			int j=0;
			for(String s : data.get("returnval").subList(start, start+length)) 
				q.values[j++] = Integer.valueOf(s)/q.scale;
			ret.put(q.name, q);
		}
		return ret;
	}
	
	public Screenshot takeScreenshot(IMachine machine) throws IOException {
	    if(machine.getState().equals(MachineState.RUNNING) || machine.getState().equals(MachineState.SAVED)) {
	        ISession session = _vbox.getSessionObject();
            machine.lockMachine(session, LockType.SHARED);
            try {
                IDisplay display = session.getConsole().getDisplay();
                Map<String, String> res = display.getScreenResolution(0);
                int width =  Integer.valueOf(res.get("width"));
                int height = Integer.valueOf(res.get("height"));
                return new Screenshot(width, height, display.takeScreenShotPNGToArray(0, width, height));
            } finally {
                session.unlockMachine();
            }
	    }
	    return null;
	}
	
	public Screenshot takeScreenshot(IMachine machine, int width, int height) throws IOException {
            ISession session = _vbox.getSessionObject();
            machine.lockMachine(session, LockType.SHARED);
            try {
                IDisplay display = session.getConsole().getDisplay();
                Map<String, String> res = display.getScreenResolution(0);
                float screenW = Float.valueOf(res.get("width"));
                float screenH = Float.valueOf(res.get("height"));
                if(screenW > screenH) {
                    float aspect = screenH/screenW;
                    height =(int) (aspect*width);
                } else if(screenH > screenW){
                    float aspect = screenW/screenH;
                    width =(int) (aspect*height);
                }
                return new Screenshot(width, height, session.getConsole().getDisplay().takeScreenShotPNGToArray(0, width, height));
            } finally {
                session.unlockMachine();
            }
    }
	
	public Screenshot readSavedScreenshot(IMachine machine, int screenId) throws IOException {
		Map<String, String> val = machine.readSavedScreenshotPNGToArray(screenId);
		return new Screenshot(Integer.valueOf(val.get("width")), Integer.valueOf(val.get("height")), Base64.decode(val.get("returnval"), 0));
	}
	
	public Screenshot readSavedThumbnail(IMachine machine, int screenId) throws IOException {
		Map<String, String> val = machine.readSavedThumbnailPNGToArray(screenId);
		return new Screenshot(Integer.valueOf(val.get("width")), Integer.valueOf(val.get("height")), Base64.decode(val.get("returnval"), 0));
	}

	/**
	 * Load network properties
	 * @param adapter		the network adapter
	 * @param names  Property names to load, or empty for all
	 * @return
	 * @throws IOException
	 */
	public Properties getProperties(INetworkAdapter adapter, String...names) throws IOException {
		StringBuffer nameString = new StringBuffer();
		for(String name : names)
			Utils.appendWithComma(nameString, name);
		return getProperties(adapter.getProperties(nameString.toString()));
	}

	/**
	 * Load medium properties
	 * @param medium		the medium
	 * @param names  Property names to load, or empty for all
	 * @return	properties
	 * @throws IOException
	 */
	public Properties getProperties(IMedium medium, String...names) throws IOException {
		StringBuffer nameString = new StringBuffer();
		for(String name : names)
			Utils.appendWithComma(nameString, name);
		return getProperties(medium.getProperties(nameString.toString()) );
	}
	
	private Properties getProperties(Map<String, List<String>> val, String...names) throws IOException {
		List<String> returnNames = val.get("returnNames");
		List<String> values = val.get("returnval");
		Properties properties = new Properties();
		for(int i=0; i<returnNames.size(); i++)
			properties.put(returnNames.get(i), values.get(i));
		return properties;
	}
	
	public Tuple<IHostNetworkInterface, IProgress> createHostOnlyNetworkInterface(IHost host) throws IOException {
		Map<String, String> val = host.createHostOnlyNetworkInterface();
		IHostNetworkInterface networkInterface = getProxy(IHostNetworkInterface.class, val.get("hostInterface"));
		IProgress progress = getProxy(IProgress.class, val.get("returnval"));
		return new Tuple<IHostNetworkInterface, IProgress>(networkInterface, progress);
	}
	
	/**
	 * Searches a DHCP server settings to be used for the given internal network name. 
	 * <p><dl><dt><b>Expected result codes:</b></dt><dd><table><tbody><tr>
	 * <td>{@link IVirtualBox#E_INVALIDARG}</td><td>Host network interface <em>name</em> already exists.  </td></tr>
	 * </tbody></table></dd></dl></p>
	 * @param name		server name
	 * @param server	DHCP server settings
	 */
	public IDHCPServer findDHCPServerByNetworkName(String name) throws IOException {
		try {
			return getVBox().findDHCPServerByNetworkName(name);
		} catch(SoapFault e) {
			Log.e(TAG, "Couldn't find DHCP Server: " + e.getMessage());
			return null;
		}
	}
	
	/**
	 * Ping a HTTPS server using SSL and prompt user to trust certificate
	 * @param handler {@link Handler} to prompt user to trust server certificate
	 * @throws IOException
	 * @throws XmlPullParserException
	 */
	public void ping(Handler handler) throws IOException, XmlPullParserException {
		SerializationEnvelope envelope = new SerializationEnvelope();
		envelope.setOutputSoapObject(new SoapObject(NAMESPACE, "IManagedObjectRef_getInterfaceName").addProperty("_this", "0"));
		envelope.setAddAdornments(false);
		new InteractiveTrustedHttpsTransport(_server, TIMEOUT, handler).call(NAMESPACE+"IManagedObjectRef_getInterfaceName", envelope);
	}
}