package com.clirq.notebook;

import nlp.utils.StaticUtils;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ExpressionEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Kernel implements Closeable {
	protected static Logger logger = LoggerFactory.getLogger(Kernel.class);
	File[] cp;
	
	Map<String, Object> objects;
	public static final Map<Thread, Map<String, Object>> objectsByThread=new HashMap<>();
	Object[] objectsA;
	Timer timer;
	File semaphor;
	long semaphoreListenerInitialDelay;
	long semaphoreListenerFrequency;
	URL[] uris;
	private List<Thread> threads;
	public static ExpressionEvaluator ee;

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
		this.semaphor=semaphore;
		this.semaphoreListenerInitialDelay=semaphoreListenerInitialDelay;
		this.semaphoreListenerFrequency=semaphoreListenerFrequency;
		objectsByThread.put(Thread.currentThread(), objects);//TODO provide a read-only copy?
	}

	/**
	 * Start a notebook kernel. 
	 * Properties file schema: 
	 * sourceDirs=';' seperated list of absolute paths. Will throw <code>NullPointerException</code> if empty. Make sure to double backslashes to escape them.  
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
		ee = new ExpressionEvaluator();
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
		Pattern implicitVar=Pattern.compile("(%([A-Za-z][A-Za-z0-9_]*))");
		Thread listener=Thread.currentThread();
		timer.schedule(new TimerTask() {
			@Override
			public void run() {
				//Clean old thread references.
				for (int i=threads.size()-1; i>=0;i--) {
					Thread t=threads.get(i);
					if (!t.isAlive()) {
						threads.remove(i);
						objectsByThread.remove(t);
					}
				}
				if (semaphor.exists()) {
					logger.info("Found semaphore: cell running too long. Interrupting");
					if (!semaphor.delete()) {
						logger.warn("Failed to delete semaphore |{}| please delete manually", semaphor);
					}
					//listener.interrupt();
					for (int i=threads.size()-1; i>=0;i--) {
						Thread t=threads.get(i);
						if (t.isAlive() && !t.isInterrupted()) {
							t.interrupt();
						}
					}
				}
			}
		}, semaphoreListenerInitialDelay, semaphoreListenerFrequency);//wait a bit before checking for the file since it would take some time for the user to create the file anyway.
		logger.info("Using source directories {}, semaphore {}, listener delay {} ms, frequency {} ms", this.cp, semaphor, semaphoreListenerInitialDelay, semaphoreListenerFrequency);
		try (Scanner scan = new Scanner(System.in)) {
			String line = "", previousInput="";
			String methodName = null, className = null;
			System.out.println("Start listening. Type $EXIT to exit");
			while (!(line = scan.nextLine()).equals("$EXIT")) {
				if (line.trim().length() == 0) {
					line=previousInput;
				}
				if (line.startsWith("\t")) {
					String expression=line.trim();
					Matcher m = implicitVar.matcher(expression);
					expression=m.replaceAll(mr ->{
						String varName=mr.group(2);
						Object val=objects.get(varName);
						if (val!=null) {
							Class<? extends Object> clz = val.getClass();
							String newExp=String.format("(%sobjects.get(\"%s\"))", 
									clz.getName().equals("Object")?"":"("+clz.getCanonicalName()+")",varName);
							return newExp;							
						}else {
							return mr.group();
						}
					});
					System.out.format("%s ==>%n", expression);
					evaluateExpression(expression);
				} else if (line.startsWith("main\t")){
					//start main method of another class in another thread.
					//schema: main	qualified class name	arguments (as space-separated string)
					String[] inputs = line.split("\t");
					className = inputs[1];// otherwise leave unchanged
					String argsString=String.join(" ", Arrays.copyOfRange(inputs, 2, inputs.length));
					String[] args=Kernel.translateCommandline(argsString);
					// A new instance of the class loader is needed to refresh the loaded class
					URLClassLoader cl = new URLClassLoader(
							uris,
							this.getClass().getClassLoader() // parentClassLoader
					);

					// The qualified class name must match the folder hierarchy
					try {
						Class<?> clz = cl.loadClass(className);
						// Call the main(args) method. No need to populate objects since presumably the main methods initializes the object.
						Method method = clz.getDeclaredMethod("main", String[].class);
						logger.info("Start main method of {} with arguments: {}", clz.getCanonicalName(), args);
						Thread t=new Thread(new Runnable() { 
						@Override
						public void run() {
							try {
								//provides a hook for the invoked main(args) method to share variables with cells and expressions
								method.invoke(null, new Object[] {args});
							} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
								// TODO Auto-generated catch block
								logger.warn("Error while trying to invoke  main method of {}: {}", clz.getCanonicalName(),  e.getMessage());
								e.printStackTrace();
							}
							
							}
						});
						objectsByThread.put(t, objects);
						t.start();
						this.threads.add(t);
					} catch (ClassNotFoundException | IllegalArgumentException | NoSuchMethodException
							| SecurityException e) {
						logger.warn("Error while tryin to invoke {}.{}: {}", className, methodName, e.getMessage());
						e.printStackTrace();
					} catch (Exception e) {
						logger.info("Exception while invoking {}.{}: {}.", className, methodName, e.getMessage());
						e.printStackTrace();
					} catch (Throwable e) {
						logger.info("Error while invoking {}.{}: {}. Consider exiting", className, methodName,
								e.getMessage());
						e.printStackTrace();
					}
					
				} else {//invoke a cell
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
						Runnable run=new Runnable() {
							@Override
							public void run() {
								try {
									method.invoke(obj);
								} catch (IllegalAccessException | IllegalArgumentException
										| InvocationTargetException e) {
									thrownError[0]=e;
								}
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
						logger.error("Error while tryin to invoke {}.{}: {}", className, methodName, e.getMessage());
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
		var previousFields = getFieldsUpTo(previous.getClass(), Object.class).stream().collect(Collectors.toMap(f -> f.getName(), Function.identity()));
			// Arrays.stream(previous.getClass().getDeclaredFields()).map(f-> f.getName()).collect(Collectors.toSet());
		var clz = obj.getClass(); //new class.
		// If a field was dropped, drop its value.
		for (var field:getFieldsUpTo(clz, Object.class)){
			if (previousFields.containsKey(field.getName())){//only move over fields existing in the previous version of the class
				var pfield = previousFields.get(field.getName());
				Object value = null;
				try {
					pfield.trySetAccessible();
					value = pfield.get(previous);
				} catch (IllegalAccessException e) {
					e.printStackTrace();
					logger.error(StaticUtils.stackTraceToString(e));
					throw e;
				}
				if (value!=null) {
					ClassLoader valueCL = value.getClass().getClassLoader();
					if (valueCL==null || valueCL.equals(parentCL)){ //immutable classes - hand over
						try {
							field.set(obj, value);
						} catch (IllegalAccessException e) {
							e.printStackTrace();
							continue;
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
								continue;
							}
						} catch (ClassNotFoundException |InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
							e.printStackTrace();
							logger.error(StaticUtils.stackTraceToString(e));
							throw e;
						}
					}
				}
			}
		}
	}
	public void closeRecursive(Object obj) {
		var parentCL = this.getClass().getClassLoader();
		var previousFields = getFieldsUpTo(obj.getClass(), Object.class).stream().collect(Collectors.toMap(f -> f.getName(), Function.identity()));
			// Arrays.stream(previous.getClass().getDeclaredFields()).map(f-> f.getName()).collect(Collectors.toSet());
		var clz = obj.getClass(); //new class.
		// If a field was dropped, drop its value.
		logger.info("Iterate over {}", clz.getCanonicalName());
		for (var field:getFieldsUpTo(clz, Object.class)){
			try {
				field.trySetAccessible();
				Object value = field.get(obj);
				if (value!=null) {
					if (value instanceof  Closeable){
						logger.info("close {}", field.toString());
						((Closeable) value).close();
					}
					var cl = value.getClass().getClassLoader();
					if (cl!=null && !parentCL.equals(cl)){//Recurse only on custom classes.
						closeRecursive(value);
					}
				}
			} catch (IllegalAccessException e) {
				continue;
			} catch (IOException e) {
				e.printStackTrace();
				logger.error("Error while trying to close {}.{}: {}", clz.getCanonicalName(), field.getName(), StaticUtils.stackTraceToString(e));
			}
		}
	}

	/**
	 * Collect the fields per class, including superclasses.
	 * Adopted from https://stackoverflow.com/questions/16966629/what-is-the-difference-between-getfields-and-getdeclaredfields-in-java-reflectio
 	 * @param startClass the class whose fields to collect
	 * @param exclusiveParent class at which to stop the climb in the class hierarchy. Null to collect fields from all superclasses.
	 * @return
	 */
	public static List<Field> getFieldsUpTo(Class<?> startClass, Class<?> exclusiveParent) {
		List<Field> currentClassFields = new ArrayList(Arrays.asList(startClass.getDeclaredFields()));
		Class<?> parentClass = startClass.getSuperclass();
		if (parentClass != null &&
				(exclusiveParent == null || !(parentClass.equals(exclusiveParent)))) {
			List<Field> parentClassFields =
					(List<Field>) getFieldsUpTo(parentClass, exclusiveParent);
			currentClassFields.addAll(parentClassFields);
		}

		return currentClassFields;
	}

	public void evaluateExpression(String expression) {
		// Now here's where the story begins...

		// The expression will have two "int" parameters: "a" and "b".
		ee.setParameters(new String[] { "objects" }, new Class[] { Map.class });

		// And the expression (i.e. "result") type is also "int".
		ee.setExpressionType(Object.class);

		// And now we "cook" (scan, parse, compile and load) the fabulous expression.
		try {
			ee.cook(expression);
			// Eventually we evaluate the expression - and that goes super-fast.
			Object result = ee.evaluate(objectsA);
			System.out.println(result);
			logger.info("{} --> {}", expression, result);
		} catch (CompileException e) {
			logger.info("Error compiling expression |{}|: {}", expression, e.getMessage());
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			logger.info("Error executing expression |{}|: {}", expression, e.getMessage());
			e.printStackTrace();
		}

	}

	@Override
	public void close() throws IOException {
		var parentCL = this.getClass().getClassLoader();
		logger.info("Closing instances");
		for (Entry<String, Object> e : objects.entrySet()) {
			String className = e.getKey();
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
	
	/**
	 * [code borrowed from ant.jar]
	 * Crack a command line.
	 * @param toProcess the command line to process.
	 * @return the command line broken into strings.
	 * An empty or null toProcess parameter results in a zero sized array.
	 */
	public static String[] translateCommandline(String toProcess) {
	    if (toProcess == null || toProcess.length() == 0) {
	        //no command? no string
	        return new String[0];
	    }
	    // parse with a simple finite state machine

	    final int normal = 0;
	    final int inQuote = 1;
	    final int inDoubleQuote = 2;
	    int state = normal;
	    final StringTokenizer tok = new StringTokenizer(toProcess, "\"\' ", true);
	    final ArrayList<String> result = new ArrayList<String>();
	    final StringBuilder current = new StringBuilder();
	    boolean lastTokenHasBeenQuoted = false;

	    while (tok.hasMoreTokens()) {
	        String nextTok = tok.nextToken();
	        switch (state) {
	        case inQuote:
	            if ("\'".equals(nextTok)) {
	                lastTokenHasBeenQuoted = true;
	                state = normal;
	            } else {
	                current.append(nextTok);
	            }
	            break;
	        case inDoubleQuote:
	            if ("\"".equals(nextTok)) {
	                lastTokenHasBeenQuoted = true;
	                state = normal;
	            } else {
	                current.append(nextTok);
	            }
	            break;
	        default:
	            if ("\'".equals(nextTok)) {
	                state = inQuote;
	            } else if ("\"".equals(nextTok)) {
	                state = inDoubleQuote;
	            } else if (" ".equals(nextTok)) {
	                if (lastTokenHasBeenQuoted || current.length() != 0) {
	                    result.add(current.toString());
	                    current.setLength(0);
	                }
	            } else {
	                current.append(nextTok);
	            }
	            lastTokenHasBeenQuoted = false;
	            break;
	        }
	    }
	    if (lastTokenHasBeenQuoted || current.length() != 0) {
	        result.add(current.toString());
	    }
	    if (state == inQuote || state == inDoubleQuote) {
	        throw new RuntimeException("unbalanced quotes in " + toProcess);
	    }
	    return result.toArray(new String[result.size()]);
	}
}
