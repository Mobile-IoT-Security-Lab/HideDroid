/**
 * Copyright (C) 2018 Ryszard Wiśniewski <brut.alll@gmail.com>
 * Copyright (C) 2018 Connor Tumbleson <connor.tumbleson@gmail.com>
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brut.androlib;

import java.util.Properties;

/**
 * @author Ryszard Wiśniewski <brut.alll@gmail.com>
 */
public class ApktoolProperties {
    public static String get(String key) {
        return get().getProperty(key);
    }

    public static Properties get() {
        if (sProps == null) {
            loadProps();
        }
        return sProps;
    }

    private static void loadProps() {
        sProps = new Properties();
        sProps.put("application.version", "2.4.0");
        String version = "2.2.5";
        sProps.put("baksmaliVersion", version);
        sProps.put("smaliVersion", version);
    }

    private static Properties sProps;
}
