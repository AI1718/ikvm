/*
  Copyright (C) 2003, 2004 Jeroen Frijters

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.

  Jeroen Frijters
  jeroen@frijters.net
  
*/
package java.lang;

import java.io.*;
import java.lang.reflect.*;

public final class ExceptionHelper
{
    // the contents of the NULL_STRING should be empty (because when the exception propagates to other .NET code
    // it will return that text as the Message property), but it *must* be a copy, because we need to be
    // able to distinguish it from a user specified blank string
    private static final String NULL_STRING = new String();
    private static final java.util.WeakHashMap exceptions = new java.util.WeakHashMap();
    // We access Runtime.defaultProperties directly, to prevent problems should an exception occur during library bootstrap
    private static final boolean cleanStackTrace = Runtime.defaultProperties.getProperty("ikvm.cleanstacktrace", "1").equals("1");
    private static cli.System.Type System_Reflection_MethodBase = cli.System.Type.GetType("System.Reflection.MethodBase, mscorlib");
    private static cli.System.Type System_Exception = cli.System.Type.GetType("System.Exception, mscorlib");

    private static class ExceptionInfoHelper
    {
	private static final Throwable CAUSE_NOT_SET = new cli.System.Exception();
	private cli.System.Diagnostics.StackTrace tracePart1;
	private cli.System.Diagnostics.StackTrace tracePart2;
	private cli.System.Collections.ArrayList stackTrace;
	private Throwable cause;

	ExceptionInfoHelper(Throwable x)
	{
	    tracePart1 = new cli.System.Diagnostics.StackTrace(x, true);
	    tracePart2 = new cli.System.Diagnostics.StackTrace(true);
	    cause = getInnerException(x);
	    if(cause == null)
	    {
		cause = CAUSE_NOT_SET;
	    }
	}

	Throwable getCauseForSerialization(Throwable t)
	{
	    return cause == CAUSE_NOT_SET ? t : cause;
	}

	Throwable get_Cause()
	{
	    return cause == CAUSE_NOT_SET ? null : cause;
	}

	void set_Cause(Throwable value)
	{
	    if(cause == CAUSE_NOT_SET)
	    {
		cause = value;
	    }
	    else
	    {
		throw new IllegalStateException("Throwable cause already initialized");
	    }
	}

	void ResetStackTrace()
	{
	    stackTrace = null;
	    tracePart1 = new cli.System.Diagnostics.StackTrace(true);
	    tracePart2 = null;
	}

	private static boolean IsPrivateScope(cli.System.Reflection.MethodBase mb)
	{
	    // HACK shouldn't there be a better way to determine whether a method is privatescope?
	    return !mb.get_IsPrivate() && !mb.get_IsFamily() && !mb.get_IsFamilyAndAssembly() &&
		    !mb.get_IsFamilyOrAssembly() && !mb.get_IsPublic();
	}

	StackTraceElement[] get_StackTrace(Throwable t)
	{
	    synchronized(this)
	    {
		if(stackTrace == null)
		{
		    stackTrace = new cli.System.Collections.ArrayList();
		    int skip1 = 0;
		    if(cleanStackTrace && t instanceof NullPointerException && tracePart1.get_FrameCount() > 0)
		    {
			// HACK if a NullPointerException originated inside an instancehelper method,
			// we assume that the reference the method was called on was really the one that was null,
			// so we filter it.
			if(tracePart1.GetFrame(0).GetMethod().get_Name().startsWith("instancehelper_") &&
			    !GetMethodName(tracePart1.GetFrame(0).GetMethod()).startsWith("instancehelper_"))
			{
			    skip1 = 1;
			}
		    }
		    Append(stackTrace, tracePart1, skip1);
		    if(tracePart2 != null)
		    {
			int skip = 0;
			if(cleanStackTrace)
			{
			    while(tracePart2.get_FrameCount() > skip && 
				tracePart2.GetFrame(skip).GetMethod().get_DeclaringType().get_FullName().startsWith("java.lang.ExceptionHelper"))
			    {
				skip++;
			    }
			    if(tracePart1.get_FrameCount() > 0 &&
				tracePart2.get_FrameCount() > skip &&
				tracePart1.GetFrame(tracePart1.get_FrameCount() - 1).GetMethod() == tracePart2.GetFrame(skip).GetMethod())
			    {
				skip++;
			    }
			}
			Append(stackTrace, tracePart2, skip);
		    }
		    if(cleanStackTrace && stackTrace.get_Count() > 0)
		    {
			StackTraceElement elem = (StackTraceElement)stackTrace.get_Item(stackTrace.get_Count() - 1);
			if(elem.getClassName().equals("java.lang.reflect.Method"))
			{
			    stackTrace.RemoveAt(stackTrace.get_Count() - 1);
			}
		    }
		    tracePart1 = null;
		    tracePart2 = null;
		}
	    }
	    StackTraceElement[] array = new StackTraceElement[stackTrace.get_Count()];
	    stackTrace.CopyTo((cli.System.Array)(Object)array);
	    return array;
	}
	
	void set_StackTrace(StackTraceElement[] value)
	{
	    stackTrace = new cli.System.Collections.ArrayList((cli.System.Collections.ICollection)(Object)value);
	    tracePart1 = null;
	    tracePart2 = null;
	}

	static void Append(cli.System.Collections.ArrayList stackTrace, cli.System.Diagnostics.StackTrace st, int skip)
	{
	    for(int i = skip; i < st.get_FrameCount(); i++)
	    {
		cli.System.Diagnostics.StackFrame frame = st.GetFrame(i);
		cli.System.Reflection.MethodBase m = frame.GetMethod();
		// TODO I may need more safety checks like these
		if(m == null || m.get_DeclaringType() == null)
		{
		    continue;
		}
		if(cleanStackTrace &&
		    (System_Reflection_MethodBase.IsAssignableFrom(m.get_DeclaringType())
		    || m.get_DeclaringType().get_FullName().startsWith("java.lang.ExceptionHelper")
		    || m.get_DeclaringType().get_FullName().equals("System.RuntimeMethodHandle")
		    || IsHideFromJava(m)
		    || IsPrivateScope(m))) // NOTE we assume that privatescope methods are always stubs that we should exclude
		{
		    continue;
		}
		String methodName = GetMethodName(frame.GetMethod());
		int lineNumber = frame.GetFileLineNumber();
		if(lineNumber == 0)
		{
		    lineNumber = GetLineNumber(frame);
		}
		String fileName = frame.GetFileName();
		if(fileName != null)
		{
		    try
		    {
			fileName = new cli.System.IO.FileInfo(fileName).get_Name();
		    }
		    catch(Throwable x)
		    {
			// Mono returns "<unknown>" for frame.GetFileName() and the FileInfo constructor
			// doesn't like that
			fileName = null;
		    }
		}
		if(fileName == null)
		{
		    fileName = GetFileName(frame);
		}
		String className = getClassNameFromType(frame.GetMethod().get_DeclaringType());
		stackTrace.Add(new StackTraceElement(fileName, lineNumber, className, methodName, IsNative(m)));
	    }
	}
    }

    private static native boolean IsHideFromJava(cli.System.Reflection.MethodBase mb);
    private static native cli.System.Exception getInnerException(Throwable t);
    private static native String getMessageFromCliException(Throwable t);
    private static native boolean IsNative(cli.System.Reflection.MethodBase mb);
    private static native String GetMethodName(cli.System.Reflection.MethodBase mb);
    private static native String getClassNameFromType(cli.System.Type type);
    private static native int GetLineNumber(cli.System.Diagnostics.StackFrame frame);
    private static native String GetFileName(cli.System.Diagnostics.StackFrame frame);
    private static native void initThrowable(Object throwable, Object detailMessage, Object cause);
    private static native Throwable MapExceptionImpl(Throwable t);

    public static void printStackTrace(Throwable x)
    {
	printStackTrace(x, System.err);
    }

    public static void printStackTrace(Throwable x, java.io.PrintStream printStream)
    {
	printStream.print(buildStackTrace(x));
    }

    public static void printStackTrace(Throwable x, java.io.PrintWriter printWriter)
    {
	printWriter.print(buildStackTrace(x));
    }

    private static String buildStackTrace(Throwable x)
    {
	if(x == null)
	{
	    throw new NullPointerException();
	}
	String newline = cli.System.Environment.get_NewLine();
	StringBuffer sb = new StringBuffer();
	sb.append(x).append(newline);
	StackTraceElement[] stack = x.getStackTrace();
	for(int i = 0; i < stack.length; i++)
	{
	    sb.append("\tat ").append(stack[i]).append(newline);
	}
	Throwable cause = x.getCause();
	while(cause != null)
	{
	    sb.append("Caused by: ").append(cause).append(newline);

	    // Cause stacktrace
	    StackTraceElement[] parentStack = stack;
	    stack = cause.getStackTrace();
	    boolean equal = false; // Is rest of stack equal to parent frame?
	    for(int i = 0; i < stack.length && !equal; i++)
	    {
		// Check if we already printed the rest of the stack
		// since it was the tail of the parent stack
		int remaining = stack.length - i;
		int element = i;
		int parentElement = parentStack.length - remaining;
		equal = parentElement >= 0 && parentElement < parentStack.length;
		while(equal && element < stack.length)
		{
		    if(stack[element].equals(parentStack[parentElement]))
		    {
			element++;
			parentElement++;
		    }
		    else
		    {
			equal = false;
		    }
		}
		// Print stacktrace element or indicate the rest is equal 
		if(!equal)
		{
		    sb.append("\tat ").append(stack[i]).append(newline);
		}
		else
		{
		    sb.append("\t... ").append(remaining).append(" more").append(newline);
		    break; // from stack printing for loop
		}
	    }
	    cause = cause.getCause();
	}
	return sb.toString();
    }

    public static Throwable initCause(Throwable x, Throwable cause)
    {
	if(x == null)
	{
	    throw new NullPointerException();
	}
	if(cause == x)
	{
	    throw new IllegalArgumentException("Cause cannot be self");
	}
	ExceptionInfoHelper eih = (ExceptionInfoHelper)exceptions.get(x);
	if(eih == null)
	{
	    eih = new ExceptionInfoHelper(x);
	    exceptions.put(x, eih);
	}
	eih.set_Cause(cause);
	return x;
    }

    public static Throwable getCause(Throwable x)
    {
	if(x == null)
	{
	    throw new NullPointerException();
	}
	ExceptionInfoHelper eih = (ExceptionInfoHelper)exceptions.get(x);
	if(eih == null)
	{
	    return getInnerException(x);
	}
	return eih.get_Cause();
    }

    public static StackTraceElement[] getStackTrace(Throwable x)
    {
	if(x == null)
	{
	    throw new NullPointerException();
	}
	ExceptionInfoHelper ei = (ExceptionInfoHelper)exceptions.get(x);
	if(ei == null)
	{
	    return new StackTraceElement[0];
	}
	return ei.get_StackTrace(x);
    }

    public static void setStackTrace(Throwable x, StackTraceElement[] stackTrace)
    {
	if(x == null)
	{
	    throw new NullPointerException();
	}
	for(int i = 0; i < stackTrace.length; i++)
	{
	    if(stackTrace[i] == null)
	    {
		throw new NullPointerException();
	    }
	}
	ExceptionInfoHelper ei = (ExceptionInfoHelper)exceptions.get(x);
	if(ei == null)
	{
	    ei = new ExceptionInfoHelper(x);
	    exceptions.put(x, ei);
	}
	ei.set_StackTrace(stackTrace);
    }

    public static String get_NullString()
    {
	return NULL_STRING;
    }

    public static String FilterMessage(String message)
    {
	if(message == null)
	{
	    message = NULL_STRING;
	}
	return message;
    }

    public static String GetMessageFromCause(Throwable cause)
    {
	if(cause == null)
	{
	    return NULL_STRING;
	}
	return cause.toString();
    }

    public static String getMessage(Throwable x)
    {
	String message = getMessageFromCliException(x);
	if(message == NULL_STRING)
	{
	    message = null;
	}
	return message;
    }

    public static String getLocalizedMessage(Throwable x)
    {
	return x.getMessage();
    }

    public static Throwable fillInStackTrace(Throwable x)
    {
	if(x == null)
	{
	    throw new NullPointerException();
	}
	ExceptionInfoHelper eih = (ExceptionInfoHelper)exceptions.get(x);
	if(eih == null)
	{
	    eih = new ExceptionInfoHelper(x);
	    exceptions.put(x, eih);
	}
	else
	{
	    eih.ResetStackTrace();
	}
	return x;
    }

    public static String toString(Throwable x)
    {
	String message = x.getLocalizedMessage();
	if(message == null)
	{
	    return x.getClass().getName();
	}
	return x.getClass().getName() + ": " + message;
    }

    public static Throwable MapExceptionFast(Throwable t)
    {
	if(exceptions.containsKey(t))
	{
	    return t;
	}
	return MapException(t, System_Exception);
    }

    public static Throwable MapException(Throwable t, cli.System.Type handler)
    {
	//cli.System.Console.WriteLine("MapException: {0}, {1}", t, handler);
	//Console.WriteLine(new StackTrace(t));
	Throwable org = t;

	t = MapExceptionImpl(t);

	if(!exceptions.containsKey(t))
	{
	    exceptions.put(t, new ExceptionInfoHelper(org));
	    Throwable inner = getInnerException(org);
	    if(inner != null && !exceptions.containsKey(inner))
	    {
		exceptions.put(inner, new ExceptionInfoHelper(inner));
	    }
	}
	return handler.IsInstanceOfType(t) ? t : null;
    }

    static ObjectStreamField[] getPersistentFields()
    {
	return new ObjectStreamField[] {
	    new ObjectStreamField("detailMessage", String.class),
	    new ObjectStreamField("cause", Throwable.class),
	    new ObjectStreamField("stackTrace", StackTraceElement[].class)
	};
    }

    static void writeObject(Throwable t, ObjectOutputStream s) throws IOException
    {
	ObjectOutputStream.PutField fields = s.putFields();
	fields.put("detailMessage", t.getMessage());
	Throwable cause;
	ExceptionInfoHelper eih = (ExceptionInfoHelper)exceptions.get(t);
	if(eih == null)
	{
	    cause = getInnerException(t);
	}
	else
	{
	    cause = eih.getCauseForSerialization(t);
	}
	fields.put("cause", cause);
	fields.put("stackTrace", t.getStackTrace());
	s.writeFields();	    
    }

    static void readObject(Throwable t, ObjectInputStream s) throws IOException
    {
	ObjectInputStream.GetField fields = null;
	try
	{
	    fields = s.readFields();
	}
	catch(ClassNotFoundException x)
	{
	    throw new IOException(x.getMessage());
	}
	initThrowable(t, fields.get("detailMessage", null), fields.get("cause", null));
	StackTraceElement[] stackTrace = (StackTraceElement[])fields.get("stackTrace", null);
	setStackTrace(t, stackTrace == null ? new StackTraceElement[0] : stackTrace);
    }
}
