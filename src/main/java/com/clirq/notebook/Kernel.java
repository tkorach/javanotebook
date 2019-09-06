package com.clirq.notebook;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ExpressionEvaluator;
import org.codehaus.janino.JavaSourceClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kernel implements Closeable {
	protected static Logger logger = LoggerFactory.getLogger(Kernel.class);
	File[] cp;

	Map<String, Object> objects;
	Object[] objectsA;
	Timer timer;
	File semaphor;
	long semaphoreListenerInitialDelay;
	long semaphoreListenerFrequency;
	public static ExpressionEvaluator ee;

	public Kernel(File[] cp, File semaphore, long semaphoreListenerInitialDelay, long semaphoreListenerFrequency) throws IOException {
		objects = new HashMap<>();
		objectsA = new Object[] { objects };
		this.cp = cp;
		this.timer=new Timer("cells");
		this.semaphor=semaphore;
		this.semaphoreListenerInitialDelay=semaphoreListenerInitialDelay;
		this.semaphoreListenerFrequency=semaphoreListenerFrequency;
	}

	/**
	 * Start a notebook kernel. 
	 * Properties file schema: 
	 * sourceDirs=';' seperated list of absolute paths. Will throw <code>NullPointerException</code> if empty. Make sure to double backslashes to escape them.  
	 * semaphore=Absolute path to semaphore file. Defaults to "stop_java". 
	 * semaphoreListenerInitialDelay= delay, in ms, untile the listener starts listening. Defaults to 3000ms.
	 * semaphoreListenerFrequency=frequency, in ms, of checking for the existence of the semaphore file. Defaults to 3000ms.
	 * 
	 * @param args single argument: Path to properties file. 
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws ClassNotFoundException
	 * @throws IllegalArgumentException
	 * @throws InvocationTargetException
	 * @throws NoSuchMethodException
	 * @throws SecurityException
	 * @throws IOException
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
				if (semaphor.exists()) {
					logger.info("Found semaphore: cell running too long. Interrupting");
					if (!semaphor.delete()) {
						logger.warn("Failed to delete semaphore |{}| please delete manually", semaphor);
					}
					listener.interrupt();
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
				} else {
					String[] inputs = line.split("\t");
					methodName = inputs[0];
					if (inputs.length > 1)
						className = inputs[1];// otherwise leave unchanged
					// A new instance of the class loader is needed to refresh the loaded class
					JavaSourceClassLoader cl = new JavaSourceClassLoader(this.getClass().getClassLoader(), // parentClassLoader
							this.cp, // optionalSourcePath
							(String) null // optionalCharacterEncoding
					);
					// debuggingInformation
					cl.setDebuggingInfo(true, true, true);

					// The qualified class name must match the folder hierarchy
					try {
						Class<?> clz = cl.loadClass(className);
						Object o = clz.getDeclaredConstructor().newInstance();
						/*
						 * Reflection instead of interface removes the dependency of the Notebook source
						 * file on this project. if (o instanceof Notebook) {
						 * ((Notebook)o).setState(objects); }
						 */
						try {

							Method setObjectsMethod = clz.getDeclaredMethod("setState", Map.class);
							setObjectsMethod.invoke(o, objects);
						} catch (NoSuchMethodException | SecurityException e) {
							// ignore - setState is not mandatory.
						}
						// Call the desired method.
						Method method = clz.getDeclaredMethod(methodName, Map.class);
						method.invoke(o, objects);						
						
					} catch (ClassNotFoundException | InstantiationException | IllegalAccessException
							| IllegalArgumentException | InvocationTargetException | NoSuchMethodException
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
				}
				previousInput=line;
				System.out.println(
						"Type <TAB><expression> or <method><TAB><class> to execute a method. Leave empty to use the current expression/method/class values. $EXIT to exit");
			}
			System.out.println("Exiting");
		}
		System.out.println("Finished");
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
		logger.info("Closing instances");
		for (Entry<String, Object> e : objects.entrySet()) {
			Object object = e.getValue();
			if (object instanceof Closeable) {
				logger.info("Attempt closing {}", e.getKey());
				try {
					((Closeable) object).close();
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}
		}
		logger.info("Finished closing instances");
	}
}
