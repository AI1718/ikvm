/*
  Copyright (C) 2011 Jeroen Frijters

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

package sun.nio.fs;

import ikvm.internal.NotYetImplementedError;
import cli.System.IO.File;
import cli.System.IO.FileMode;
import cli.System.IO.FileShare;
import cli.System.IO.FileStream;
import cli.System.IO.FileOptions;
import cli.System.Security.AccessControl.FileSystemRights;
import com.sun.nio.file.ExtendedOpenOption;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import sun.nio.ch.FileChannelImpl;

final class NetFileSystemProvider extends AbstractFileSystemProvider
{
    private final NetFileSystem fs = new NetFileSystem(this);

    public String getScheme()
    {
        return "file";
    }

    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException
    {
        throw new FileSystemAlreadyExistsException();
    }

    public FileSystem getFileSystem(URI uri)
    {
        return fs;
    }

    public Path getPath(URI uri)
    {
        throw new NotYetImplementedError();
    }

    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> opts, FileAttribute<?>... attrs) throws IOException
    {
        return newFileChannel(path, opts, attrs);
    }

    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> opts, FileAttribute<?>... attrs) throws IOException
    {
        NetPath npath = NetPath.from(path);
        if (attrs.length != 0)
        {
            throw new NotYetImplementedError();
        }
        int mode = FileMode.Open;
        int share = FileShare.ReadWrite | FileShare.Delete;
        int options = FileOptions.None;
        boolean read = false;
        boolean write = false;
        boolean append = false;
        boolean sparse = false;
        for (OpenOption opt : opts)
        {
            if (opt instanceof StandardOpenOption)
            {
                switch ((StandardOpenOption)opt)
                {
                    case APPEND:
                        append = true;
                        write = true;
                        mode = FileMode.Append;
                        break;
                    case CREATE:
                        mode = FileMode.Create;
                        break;
                    case CREATE_NEW:
                        mode = FileMode.CreateNew;
                        break;
                    case DELETE_ON_CLOSE:
                        options |= FileOptions.DeleteOnClose;
                        break;
                    case DSYNC:
                        options |= FileOptions.WriteThrough;
                        break;
                    case READ:
                        read = true;
                        break;
                    case SPARSE:
                        sparse = true;
                        break;
                    case SYNC:
                        options |= FileOptions.WriteThrough;
                        break;
                    case TRUNCATE_EXISTING:
                        mode = FileMode.Truncate;
                        break;
                    case WRITE:
                        write = true;
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
            }
            else if (opt instanceof ExtendedOpenOption)
            {
                switch ((ExtendedOpenOption)opt)
                {
                    case NOSHARE_READ:
                        share &= ~FileShare.Read;
                        break;
                    case NOSHARE_WRITE:
                        share &= ~FileShare.Write;
                        break;
                    case NOSHARE_DELETE:
                        share &= ~FileShare.Delete;
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
            }
            else if (opt == null)
            {
                throw new NullPointerException();
            }
            else
            {
                throw new UnsupportedOperationException();
            }
        }

        if (!read && !write)
        {
            read = true;
        }

        if (read && append)
        {
            throw new IllegalArgumentException("READ + APPEND not allowed");
        }
        
        if (append && mode == FileMode.Truncate)
        {
            throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
        }
        
        if (mode == FileMode.CreateNew && sparse)
        {
            throw new UnsupportedOperationException();
        }

        int rights = 0;
        if (append)
        {
            // for atomic append to work, we can't set FileSystemRights.Write
            rights |= FileSystemRights.AppendData;
        }
        else
        {
            if (read)
            {
                rights |= FileSystemRights.Read;
            }
            if (write)
            {
                rights |= FileSystemRights.Write;
            }
        }

        FileStream fs;
        try
        {
            if (false) throw new cli.System.ArgumentException();
            if (false) throw new cli.System.IO.FileNotFoundException();
            if (false) throw new cli.System.IO.DirectoryNotFoundException();
            if (false) throw new cli.System.PlatformNotSupportedException();
            if (false) throw new cli.System.IO.IOException();
            if (false) throw new cli.System.Security.SecurityException();
            if (false) throw new cli.System.UnauthorizedAccessException();
            fs = new FileStream(npath.path, FileMode.wrap(mode), FileSystemRights.wrap(rights), FileShare.wrap(share), 8, FileOptions.wrap(options));
        }
        catch (cli.System.ArgumentException x)
        {
            throw new FileSystemException(npath.path, null, x.getMessage());
        }
        catch (cli.System.IO.FileNotFoundException _)
        {
            throw new NoSuchFileException(npath.path);
        }
        catch (cli.System.IO.DirectoryNotFoundException _)
        {
            throw new NoSuchFileException(npath.path);
        }
        catch (cli.System.PlatformNotSupportedException x)
        {
            throw new UnsupportedOperationException(x.getMessage());
        }
        catch (cli.System.IO.IOException x)
        {
            throw new FileSystemException(npath.path, null, x.getMessage());
        }
        catch (cli.System.Security.SecurityException _)
        {
            throw new AccessDeniedException(npath.path);
        }
        catch (cli.System.UnauthorizedAccessException _)
        {
            throw new AccessDeniedException(npath.path);
        }
        return FileChannelImpl.open(FileDescriptor.fromStream(fs), read, write, append, null);
    }

    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        throw new NotYetImplementedError();
    }

    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException
    {
        throw new NotYetImplementedError();
    }

    public void copy(Path source, Path target, CopyOption... options) throws IOException
    {
        NetPath nsource = NetPath.from(source);
        NetPath ntarget = NetPath.from(target);
        boolean overwrite = false;
        for (CopyOption opt : options)
        {
            if (opt == StandardCopyOption.REPLACE_EXISTING)
            {
                overwrite = true;
            }
            else
            {
                throw new UnsupportedOperationException("Unsupported copy option");
            }
        }
        try
        {
            if (false) throw new cli.System.ArgumentException();
            if (false) throw new cli.System.IO.FileNotFoundException();
            if (false) throw new cli.System.IO.DirectoryNotFoundException();
            if (false) throw new cli.System.IO.IOException();
            if (false) throw new cli.System.Security.SecurityException();
            if (false) throw new cli.System.UnauthorizedAccessException();
            File.Copy(nsource.path, ntarget.path, overwrite);
        }
        catch (cli.System.IO.FileNotFoundException x)
        {
            throw new NoSuchFileException(x.get_FileName());
        }
        catch (cli.System.IO.DirectoryNotFoundException x)
        {
            throw new NoSuchFileException(nsource.path, ntarget.path, x.getMessage());
        }
        catch (cli.System.IO.IOException | cli.System.ArgumentException x)
        {
            throw new FileSystemException(nsource.path, ntarget.path, x.getMessage());
        }
        catch (cli.System.Security.SecurityException | cli.System.UnauthorizedAccessException x)
        {
            throw new AccessDeniedException(nsource.path, ntarget.path, x.getMessage());
        }
    }

    public void move(Path source, Path target, CopyOption... options) throws IOException
    {
        NetPath nsource = NetPath.from(source);
        NetPath ntarget = NetPath.from(target);
        for (CopyOption opt : options)
        {
            if (opt == StandardCopyOption.ATOMIC_MOVE)
            {
                throw new AtomicMoveNotSupportedException(nsource.path, ntarget.path, "Unsupported copy option");
            }
            else
            {
                throw new UnsupportedOperationException("Unsupported copy option");
            }
        }
        try
        {
            if (false) throw new cli.System.ArgumentException();
            if (false) throw new cli.System.IO.FileNotFoundException();
            if (false) throw new cli.System.IO.DirectoryNotFoundException();
            if (false) throw new cli.System.IO.IOException();
            if (false) throw new cli.System.Security.SecurityException();
            if (false) throw new cli.System.UnauthorizedAccessException();
            File.Move(nsource.path, ntarget.path);
        }
        catch (cli.System.IO.FileNotFoundException x)
        {
            throw new NoSuchFileException(x.get_FileName());
        }
        catch (cli.System.IO.DirectoryNotFoundException x)
        {
            throw new NoSuchFileException(nsource.path, ntarget.path, x.getMessage());
        }
        catch (cli.System.IO.IOException | cli.System.ArgumentException x)
        {
            throw new FileSystemException(nsource.path, ntarget.path, x.getMessage());
        }
        catch (cli.System.Security.SecurityException | cli.System.UnauthorizedAccessException x)
        {
            throw new AccessDeniedException(nsource.path, ntarget.path, x.getMessage());
        }
    }

    public boolean isSameFile(Path path, Path path2) throws IOException
    {
        if (path.equals(path2))
        {
            return true;
        }
        if (!(path instanceof NetPath && path2 instanceof NetPath))
        {
            return false;
        }
        return path.toRealPath().equals(path2.toRealPath());
    }

    public boolean isHidden(Path path) throws IOException
    {
        throw new NotYetImplementedError();
    }

    public FileStore getFileStore(Path path) throws IOException
    {
        throw new NotYetImplementedError();
    }

    public void checkAccess(Path path, AccessMode... modes) throws IOException
    {
        throw new NotYetImplementedError();
    }

    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options)
    {
        throw new NotYetImplementedError();
    }

    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException
    {
        throw new NotYetImplementedError();
    }

    DynamicFileAttributeView getFileAttributeView(Path file, String name, LinkOption... options)
    {
        return null;
    }

    boolean implDelete(Path file, boolean failIfNotExists) throws IOException
    {
        String path = NetPath.from(file).path;
        SecurityManager sm = System.getSecurityManager();
        if (sm != null)
        {
            sm.checkDelete(path);
        }
        try
        {
            if (false) throw new cli.System.ArgumentException();
            if (false) throw new cli.System.IO.FileNotFoundException();
            if (false) throw new cli.System.IO.DirectoryNotFoundException();
            if (false) throw new cli.System.IO.IOException();
            if (false) throw new cli.System.Security.SecurityException();
            if (false) throw new cli.System.UnauthorizedAccessException();
            int attr = cli.System.IO.File.GetAttributes(path).Value;
            if ((attr & cli.System.IO.FileAttributes.Directory) != 0)
            {
                cli.System.IO.Directory.Delete(path);
                return true;
            }
            else
            {
                cli.System.IO.File.Delete(path);
                return true;
            }
        }
        catch (cli.System.ArgumentException x)
        {
            throw new FileSystemException(path, null, x.getMessage());
        }
        catch (cli.System.IO.FileNotFoundException _)
        {
            if (failIfNotExists)
            {
                throw new NoSuchFileException(path);
            }
            else
            {
                return false;
            }
        }
        catch (cli.System.IO.DirectoryNotFoundException _)
        {
            if (failIfNotExists)
            {
                throw new NoSuchFileException(path);
            }
            else
            {
                return false;
            }
        }
        catch (cli.System.IO.IOException x)
        {
            throw new FileSystemException(path, null, x.getMessage());
        }
        catch (cli.System.Security.SecurityException _)
        {
            throw new AccessDeniedException(path);
        }
        catch (cli.System.UnauthorizedAccessException _)
        {
            throw new AccessDeniedException(path);
        }
    }
}
