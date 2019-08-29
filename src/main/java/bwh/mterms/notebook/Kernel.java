package bwh.mterms.notebook;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;


import org.codehaus.janino.JavaSourceClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Kernel implements Closeable {
	protected static Logger logger = LoggerFactory.getLogger(Kernel.class);
	File[] cp;
	
	Map<String, Object> objects;

    public Kernel(File[] cp) {
    	objects=new HashMap<>();
    	this.cp=cp;
	}
	public static void main( String[] args ) throws InstantiationException, IllegalAccessException, ClassNotFoundException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException{
		File[] cp={
				new File(".\\src\\main\\java\\bwh\\mterms\\notebook"),
				new File("F:\\programs\\nbs"),
				new File("F:\\git\\mterms\\java\\phrasemining\\src\\main\\java")
				};
		try(Kernel kernel = new Kernel(cp)){
			kernel.listen();			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
	public  void listen() {
		try(Scanner scan=new Scanner(System.in)){
	        String line="";
	        String methodName=null, className=null;
	        System.out.println("Start listening. Type $EXIT to exit");
	        while(!(line=scan.nextLine()).equals("$EXIT")) {
	        	if (line.trim().length()>0) {
					String[] inputs = line.split("\t");
					methodName = inputs[0];
					if (inputs.length > 1)
						className = inputs[1];//otherwise leave unchanged
				}
	        	//A new instance of the class loader is needed to refresh the loaded class
	        	JavaSourceClassLoader cl = new JavaSourceClassLoader(
	        			this.getClass().getClassLoader(),  // parentClassLoader
	        			this.cp, // optionalSourcePath
	        			(String) null                     // optionalCharacterEncoding
	        			);
	        	// debuggingInformation
	        	cl.setDebuggingInfo(true, true, true);
	
				// The qualified class name must match the folder hierarchy
				try {
					Class<?> clz = cl.loadClass(className);
					Object o = clz.getDeclaredConstructor().newInstance();
					/*
					 * Reflection instead of interface removes the dependency of the Notebook source file on this project.  
					if (o instanceof Notebook) {
						((Notebook)o).setState(objects);
					}
					*/
					try {
						
						Method setObjectsMethod = clz.getDeclaredMethod("setState", Map.class);
						setObjectsMethod.invoke(o, objects);
					} catch (NoSuchMethodException | SecurityException  e) {
						// ignore - setState is not mandatory. 
					}
					//Call the desired method.
					Method method = clz.getDeclaredMethod(methodName, Map.class);
					method.invoke(o, objects);
				} catch (ClassNotFoundException | InstantiationException | IllegalAccessException | IllegalArgumentException
						| InvocationTargetException | NoSuchMethodException | SecurityException e) {
					logger.warn("Error while tryin to invoke {}.{}: {}", className, methodName, e.getMessage());
					e.printStackTrace();
				} catch (Exception e) {
					logger.info("Exception while invoking {}.{}: {}.", className, methodName, e.getMessage() );
					e.printStackTrace();
				} catch (Throwable e) {
		        	logger.info("Error while invoking {}.{}: {}. Consider exiting", className, methodName, e.getMessage() );
		        	e.printStackTrace();
		        }
				System.out.println("Type <method><TAB><class> to execute. Leave empty to use current values. $EXIT to exit");
	        }
	        System.out.println("Exiting");
		}
        System.out.println("Finished");
	}
	@Override
	public void close() throws IOException {
		logger.info("Closing instances");
		for (Entry<String, Object> e:objects.entrySet()) {
			Object object = e.getValue();
			if (object instanceof Closeable) {
				logger.info("Attempt closing {}", e.getKey());
				try {
					((Closeable)object).close();
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}
		}
		logger.info("Finished closing instances");
	}
}
