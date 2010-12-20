/*
  Copyright (C) 2009, 2010 Volker Berlin (i-net software)
  Copyright (C) 2010 Karsten Heinrich (i-net software)

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
package java_.awt;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;


@RunWith(Suite.class)
@Suite.SuiteClasses({
    ButtonTest.class,
    DialogTest.class,
    FontMetricsTest.class,
    FontTest.class,
    FrameTest.class,
    GraphicsTest.class,
    GraphicsDeviceTest.class,
    GraphicsEnvironmentTest.class,
    LabelTest.class,
    PanelTest.class,
    ToolkitTest.class,
    WindowTest.class
})
public class AllTests{
    //Nothing
}
