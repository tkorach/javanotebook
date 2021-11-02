package com.clirq.notebook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Kernel implements Closeable {
	protected static Logger logger = LoggerFactory.getLogger(Kernel.class);
	File[] cp;
	
	Map<String, Object> objects;
	Object[] objectsA;
	Timer timer;
	File semaphore;
	long semaphoreListenerInitialDelay;
	long semaphoreListenerFrequency;
	URL[] uris;
	private final List<Thread> threads;

	public Kernel(File[] cp, File semaphore, long semaphoreListenerInitialDelay, long semaphoreListenerFrequency) throws IOException {
		objects = new HashMap<>();
		objectsA = new Object[] { objects };
		this.cp = cp;
		this.uris=new URL[this.cp.length];
		for (int i = 0; i < uris.length; i++) {
			uris[i]=cp[i].toURI().toURL();
		}
		this.timer=new Timer("cells");
		this.threads=new ArrayList<>();
		this.semaphore =semaphore;
		this.semaphoreListenerInitialDelay=semaphoreListenerInitialDelay;
		this.semaphoreListenerFrequency=semaphoreListenerFrequency;
	}

	/**
	 * Start a notebook kernel. 
	 * Properties file schema: 
	 * sourceDirs=';' separated list of absolute paths. Will throw <code>NullPointerException</code> if empty. Make sure to double backslashes to escape them.
	 * semaphore=Absolute path to semaphore file. Defaults to "stop_java". 
	 * semaphoreListenerInitialDelay= delay, in milliseconds (ms), until the listener starts listening. Defaults to 3000ms.
	 * semaphoreListenerFrequency=frequency (in ms) of checking for the existence of the semaphore file. Defaults to 3000ms.
	 * 
	 * @param args single argument: Path to properties file.
	 */
	public static void main(String[] args)
			throws InstantiationException, IllegalAccessException, ClassNotFoundException, IllegalArgumentException,
			InvocationTargetException, NoSuchMethodException, SecurityException, IOException {
		File pFile = new File(args[0]);
		Properties props = new Properties();
		try(BufferedReader br=Files.newBufferedReader(pFile.toPath())){
			props.load(br);
			
			List<File> cp = new ArrayList<>();
			//intentionally throw a NPE on empty value
			for (String a : ((String)props.get("sourceDirs")).split(";")) {
				File f=new File(a);
				if (f.exists())
					cp.add(f);
				else {
					logger.info("Argument |{}| is not an existing directory", a);
				}
			}
			File semaphore=new File(props.getProperty("semaphore", "stop_java"));
			long semaphoreListenerInitialDelay=Long.parseLong(props.getProperty("semaphoreListenerInitialDelay", "3000"));
			long semaphoreListenerFrequency=Long.parseLong(props.getProperty("semaphoreListenerFrequency", "3000"));
			try (Kernel kernel = new Kernel(cp.toArray(new File[0]), semaphore, semaphoreListenerInitialDelay, semaphoreListenerFrequency)) {
				kernel.listen();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	public void listen() {
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				//Clean old thread references.
				for (int i=threads.size()-1; i>=0;i--) {
					Thread t=threads.get(i);
					if (!t.isAlive()) {
						threads.remove(i);
					}
				}
				if (semaphore.exists()) {
					logger.info("Found semaphore: cell running too long. Interrupting");
					if (!semaphore.delete()) {
						logger.warn("Failed to delete semaphore |{}| please delete manually", semaphore);
					}
					for (int i=threads.size()-1; i>=0;i--) {
						Thread t=threads.get(i);
						if (t.isAlive() && !t.isInterrupted()) {
							t.interrupt();
						}
					}
				}
			}
		}, semaphoreListenerInitialDelay, semaphoreListenerFrequency);//wait a bit before checking for the file since it would take some time for the user to create the file anyway.
		logger.info("Using source directories {}, semaphore {}, listener delay {} ms, frequency {} ms", this.cp, semaphore, semaphoreListenerInitialDelay, semaphoreListenerFrequency);
		try (Scanner scan = new Scanner(System.in)) {
			String line = "", previousInput="";
			String methodName, className = null;
			System.out.println("Start listening. Type $EXIT to exit");
			while (!(line = scan.nextLine()).equals("$EXIT")) {
				if (line.trim().length() == 0) {
					line=previousInput;
				}
				{//invoke a cell
					String[] inputs = line.split("\t");
					methodName = inputs[0];
					if (inputs.length > 1)
						className = inputs[1];// otherwise leave unchanged
					// A new instance of the class loader is needed to refresh the loaded class
					URLClassLoader cl = new URLClassLoader(
							uris,
							this.getClass().getClassLoader() // parentClassLoader
					);

					// The qualified class name must match the folder hierarchy
					try {
						Class<?> clz = cl.loadClass(className);
						Object obj = clz.getDeclaredConstructor().newInstance();

						Object previous = objects.get(className);
						if (previous!=null){
							populateFields(cl, previous, obj);
						}
						//Enforces a single instance per class!
						objects.put(className, obj); //replace the previous instance with the new one after copying over
						// Call the desired method.
						Method method = clz.getDeclaredMethod(methodName);
						Throwable[] thrownError=new Throwable[1];
						Runnable run=() -> {
								try {
									method.invoke(obj);
								} catch (IllegalAccessException | IllegalArgumentException
										| InvocationTargetException e) {
									thrownError[0]=e;
								}
						};
						Thread t=new Thread(run,methodName);
						this.threads.add(t);
						t.start();
						t.join();//TODO Consider parameterizing. Currently: wait for thread to end. 
						if (thrownError[0]!=null) {
							throw thrownError[0];
						}
					} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
							| IllegalArgumentException | InvocationTargetException | NoSuchMethodException
							| SecurityException e) {
						logger.error("Error while trying to invoke {}.{}: {}", className, methodName, e.getMessage());
						e.printStackTrace();
					} catch (Exception e) {
						logger.error("Exception while invoking {}.{}: {}.", className, methodName, e.getMessage());
						e.printStackTrace();
					} catch (Throwable e) {
						logger.error("Error while invoking {}.{}: {}. Consider exiting", className, methodName,
								e.getMessage());
						e.printStackTrace();
					}
				}
				previousInput=line;
				System.out.println(
						"Type <TAB><expression> or <method><TAB><class> to execute a method. Leave empty to use the current expression/method/class values. $EXIT to exit");
			}
			System.out.println("Exiting");
		}
		System.out.println("Finished");
	}

	public <T> void populateFields(ClassLoader currentCL, T previous, T obj) throws ClassNotFoundException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchMethodException {
		var parentCL = this.getClass().getClassLoader();
		var previousFields = getFieldsUpTo(previous.getClass(), Object.class).stream().collect(Collectors.toMap(Field::getName, Function.identity()));
			// Arrays.stream(previous.getClass().getDeclaredFields()).map(f-> f.getName()).collect(Collectors.toSet());
		var clz = obj.getClass(); //new class.
		// If a field was dropped, drop its value.
		for (var field:getFieldsUpTo(clz, Object.class)){
			if (previousFields.containsKey(field.getName())){//only move over fields existing in the previous version of the class
				var pfield = previousFields.get(field.getName());
				Object value;
				try {
					pfield.trySetAccessible();
					value = pfield.get(previous);
				} catch (IllegalAccessException e) {
					e.printStackTrace();
					logger.error(stackTraceToString(e));
					throw e;
				}
				if (value!=null) {
					ClassLoader valueCL = value.getClass().getClassLoader();
					if (valueCL==null || valueCL.equals(parentCL)){ //immutable classes - hand over
						try {
							field.set(obj, value);
						} catch (IllegalAccessException e) {
							e.printStackTrace();
							//continue;
						}
					} else{ //Attempt recreating the object
						String className = value.getClass().getCanonicalName();
						try {
							Class<?> nclz = currentCL.loadClass(className);
							Object sobj = nclz.getDeclaredConstructor().newInstance();
							populateFields(currentCL, value, sobj);

							//place the populated sub-object in the new object
							try {
								field.set(obj, sobj);
							} catch (IllegalAccessException e) {
								e.printStackTrace();
								//continue;
							}
						} catch (ClassNotFoundException |InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
							e.printStackTrace();
							logger.error(stackTraceToString(e));
							throw e;
						}
					}
				}
			}
		}
	}
	public void closeRecursive(Object obj) {
		var parentCL = this.getClass().getClassLoader();
		var clz = obj.getClass(); //new class.
		logger.info("Iterate over {}", clz.getCanonicalName());
		for (var field:getFieldsUpTo(clz, Object.class)){
			try {
				field.trySetAccessible();
				Object value = field.get(obj);
				if (value!=null) {
					if (value instanceof  Closeable){
						logger.info("close {}", field);
						((Closeable) value).close();
					}
					var cl = value.getClass().getClassLoader();
					if (cl!=null && !parentCL.equals(cl)){//Recurse only on custom classes.
						closeRecursive(value);
					}
				}
			} catch (IllegalAccessException e) {
				//continue;
			} catch (IOException e) {
				e.printStackTrace();
				logger.error("Error while trying to close {}.{}: {}", clz.getCanonicalName(), field.getName(), stackTraceToString(e));
			}
		}
	}

	/**
	 * Collect the fields per class, including superclasses.
	 * Adopted from https://stackoverflow.com/questions/16966629/what-is-the-difference-between-getfields-and-getdeclaredfields-in-java-reflectio
 	 * @param startClass the class whose fields to collect
	 * @param exclusiveParent class at which to stop the climb in the class hierarchy. Null to collect fields from all superclasses.
	 * @return List of fields in this class an its parents up to exclusive parent.
	 */
	public static List<Field> getFieldsUpTo(Class<?> startClass, Class<?> exclusiveParent) {
		List<Field> currentClassFields = new ArrayList<>(Arrays.asList(startClass.getDeclaredFields()));
		Class<?> parentClass = startClass.getSuperclass();
		if (parentClass != null &&
				(exclusiveParent == null || !(parentClass.equals(exclusiveParent)))) {
			List<Field> parentClassFields = getFieldsUpTo(parentClass, exclusiveParent);
			currentClassFields.addAll(parentClassFields);
		}

		return currentClassFields;
	}

	@Override
	public void close() throws IOException {
		logger.info("Closing instances");
		for (Entry<String, Object> e : objects.entrySet()) {
			Object object = e.getValue();
			closeRecursive(object);
		}
		timer.cancel();
		for (Thread t: threads) {
			if (t.isAlive()) {
				logger.info("Attempt to interrupt thread {}, wait for {} ms", t.getName(), this.semaphoreListenerInitialDelay);
				t.interrupt();
				try {
					t.join(this.semaphoreListenerInitialDelay);
					logger.info("Closed thread {}", t.getName());
				} catch (InterruptedException e1) {
					logger.info("Thread {} timed-out. Please shut down JVM manually", t.getName());
				}
			}
		}
		logger.info("Finished closing instances");
	}

	public static String stackTraceToString(Throwable e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		String sStackTrace = sw.toString();
		return sStackTrace;
	}
}
