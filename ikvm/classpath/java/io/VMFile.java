/*
  Copyright (C) 2004 Jeroen Frijters

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
package java.io;

import gnu.java.io.PlatformHelper;

final class VMFile
{
    // TODO set this correctly
    static boolean caseSensitive = true;

    // On Windows, an absolute path can contain a bogus leading slash (and backslash?)
    static String demangle(String path)
    {
	if(path.length() > 3 && (path.charAt(0) == '\\' || path.charAt(0) == '/') && path.charAt(2) == ':')
	{
	    return path.substring(1);
	}
	return path;
    }

    private static long DateTimeToJavaLongTime(cli.System.DateTime datetime)
    {
	return cli.System.DateTime.op_Subtraction(cli.System.TimeZone.get_CurrentTimeZone().ToUniversalTime(datetime), new cli.System.DateTime(1970, 1, 1)).get_Ticks() / 10000L;
    }

    private static cli.System.DateTime JavaLongTimeToDateTime(long datetime)
    {
	return cli.System.TimeZone.get_CurrentTimeZone().ToLocalTime(new cli.System.DateTime(new cli.System.DateTime(1970, 1, 1).get_Ticks() + datetime * 10000L));
    }

    static long lastModified(String path)
    {
	try
	{
	    // TODO what if "path" is a directory?
	    return DateTimeToJavaLongTime(cli.System.IO.File.GetLastWriteTime(demangle(path)));
	}
	catch(Throwable x)
	{
	    return 0;
	}
    }

    static boolean setReadOnly(String path)
    {
	try
	{
	    cli.System.IO.FileInfo fileInfo = new cli.System.IO.FileInfo(demangle(path));
	    cli.System.IO.FileAttributes attr = fileInfo.get_Attributes();
	    attr = cli.System.IO.FileAttributes.wrap(attr.Value | cli.System.IO.FileAttributes.ReadOnly);
	    fileInfo.set_Attributes(attr);
	    return true;
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean create(String path) throws IOException
    {
	try
	{
	    cli.System.IO.File.Open(demangle(path), cli.System.IO.FileMode.wrap(cli.System.IO.FileMode.CreateNew)).Close();
	    return true;
	}
	catch(Throwable x)
	{
	    // TODO handle errors
	    return false;
	}
    }

    static String[] list(String dirpath)
    {
	// TODO error handling
	try
	{
	    String[] l = cli.System.IO.Directory.GetFileSystemEntries(demangle(dirpath));
	    for(int i = 0; i < l.length; i++)
	    {
		int pos = l[i].lastIndexOf(cli.System.IO.Path.DirectorySeparatorChar);
		if(pos >= 0)
		{
		    l[i] = l[i].substring(pos + 1);
		}
	    }
	    return l;
	}
	catch(Throwable x)
	{
	    return null;
	}
    }

    static boolean renameTo(String targetpath, String destpath)
    {
	try
	{
	    new cli.System.IO.FileInfo(demangle(targetpath)).MoveTo(demangle(destpath));
	    return true;
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static long length(String path)
    {
	// TODO handle errors
	try
	{
	    return new cli.System.IO.FileInfo(demangle(path)).get_Length();
	}
	catch(Throwable x)
	{
	    return 0;
	}
    }

    static boolean exists(String path)
    {
	path = demangle(path);
	try
	{
	    return cli.System.IO.File.Exists(path) || cli.System.IO.Directory.Exists(path);
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean delete(String path)
    {
	// TODO handle errors
	try
	{
	    if(cli.System.IO.Directory.Exists(path)) 
	    {
		cli.System.IO.Directory.Delete(path);
	    } 
	    else if(cli.System.IO.File.Exists(path)) 
	    {
		cli.System.IO.File.Delete(path);
	    } 
	    else 
	    {
		return false;
	    }
	    return true;
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean setLastModified(String path, long time)
    {
	try
	{
	    new cli.System.IO.FileInfo(demangle(path)).set_LastWriteTime(JavaLongTimeToDateTime(time));
	    return true;
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean mkdir(String path)
    {
	path = demangle(path);
	// TODO handle errors
	if (!cli.System.IO.Directory.Exists(cli.System.IO.Directory.GetParent(path).get_FullName()) ||
	    cli.System.IO.Directory.Exists(path))
	{
	    return false;
	}
	return cli.System.IO.Directory.CreateDirectory(path) != null;
    }

    static boolean isFile(String path)
    {
	// TODO handle errors
	// TODO make sure semantics are the same
	try
	{
	    return cli.System.IO.File.Exists(demangle(path));
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean canWrite(String path)
    {
	path = demangle(path);
	try
	{
	    // HACK if file refers to a directory, we always return true
	    if(cli.System.IO.Directory.Exists(path))
	    {
		return true;
	    }
	    new cli.System.IO.FileInfo(path).Open(
		cli.System.IO.FileMode.wrap(cli.System.IO.FileMode.Open),
		cli.System.IO.FileAccess.wrap(cli.System.IO.FileAccess.Write),
		cli.System.IO.FileShare.wrap(cli.System.IO.FileShare.ReadWrite)).Close();
	    return true;
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean canRead(String path)
    {
	path = demangle(path);
	try
	{
	    // HACK if file refers to a directory, we always return true
	    if(cli.System.IO.Directory.Exists(path))
	    {
		return true;
	    }
	    new cli.System.IO.FileInfo(path).Open(
		cli.System.IO.FileMode.wrap(cli.System.IO.FileMode.Open),
		cli.System.IO.FileAccess.wrap(cli.System.IO.FileAccess.Read),
		cli.System.IO.FileShare.wrap(cli.System.IO.FileShare.ReadWrite)).Close();
	    return true;
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static boolean isDirectory(String path)
    {
	// TODO handle errors
	// TODO make sure semantics are the same
	try
	{
	    return cli.System.IO.Directory.Exists(demangle(path));
	}
	catch(Throwable x)
	{
	    return false;
	}
    }

    static File[] listRoots()
    {
	String[] roots = cli.System.IO.Directory.GetLogicalDrives();
	File[] fileRoots = new File[roots.length];
	for(int i = 0; i < roots.length; i++)
	{
	    fileRoots[i] = new File(roots[i]);
	}
	return fileRoots;
    }

    static boolean isHidden(String path)
    {
	path = demangle(path);
	if(cli.System.IO.Directory.Exists(path))
	{
	    return (new cli.System.IO.DirectoryInfo(path).get_Attributes().Value & cli.System.IO.FileAttributes.Hidden) != 0;
	}
	else
	{
	    return (new cli.System.IO.FileInfo(path).get_Attributes().Value & cli.System.IO.FileAttributes.Hidden) != 0;
	}
    }

    static String getName(String path)
    {
	return new cli.System.IO.FileInfo(demangle(path)).get_Name();
    }
}
