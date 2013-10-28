/**
 * 
 */
package com.preplay.android.i18next;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Log;

/**
 * @author stan
 * 
 */
public class I18Next {
    /** Used locally to tag Logs */
    private static final String TAG = I18Next.class.getSimpleName();

    private Options mOptions = new Options();
    private JSONObject mRootObject = new JSONObject();

    /**
     * SingletonHolder is loaded on the first execution of Singleton.getInstance()
     * or the first access to SingletonHolder.INSTANCE, not before.
     */
    private static class SingletonHolder {
        public static final I18Next INSTANCE = new I18Next();
    }

    private I18Next() {
    }

    public static I18Next getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private enum LogMode {
        VERBOSE, WARNING, ERROR;
    }

    public Options getOptions() {
        return mOptions;
    }

    public void load(String lang, String namespace, String json) throws JSONException {
        load(lang, namespace, new JSONObject(json));
    }

    public void load(String lang, String namespace, JSONObject json) throws JSONException {
        mRootObject.put(namespace, json);
    }

    public void load(Context context, String namespace, int resource) throws JSONException, IOException {
        load(context, Locale.getDefault().toString(), namespace, resource);
    }

    public void load(Context context, String lang, String namespace, int resource) throws JSONException, IOException {
        String json = null;
        try {
            json = context.getResources().getString(resource);
        } catch (Exception ex) {
        }
        if (json == null) {
            InputStream inputStream;
            try {
                inputStream = context.getResources().openRawResource(resource);
            } catch (Exception ex) {
                inputStream = null;
            }
            if (inputStream != null) {
                InputStreamReader is = new InputStreamReader(inputStream);
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(is);
                String read = br.readLine();
                while (read != null) {
                    sb.append(read);
                    read = br.readLine();
                }
                json = sb.toString();
            }
        }
        if (json == null) {
            throw new IOException("File not found");
        } else {
            load(lang, namespace, json);
        }
    }

    private void log(String raw, Object... args) {
        log(LogMode.VERBOSE, raw, args);
    }

    private void log(LogMode logMode, String raw, Object... args) {
        if (mOptions.isDebugMode()) {
            if (args != null) {
                raw = String.format(raw, args);
            }
            switch (logMode) {
                case ERROR:
                    Log.e(TAG, raw);
                    break;
                case WARNING:
                    Log.w(TAG, raw);
                    break;
                case VERBOSE:
                    Log.v(TAG, raw);
                    break;

            }
        }
    }

    private String[] splitKeyPath(String key) {
        if (key != null) {
            String[] splitKeys = key.split(Pattern.quote(mOptions.getKeySeparator()));
            if (splitKeys == null) {
                log(LogMode.ERROR, "impossible to split key '%s'", key);
            }
            return splitKeys;
        }
        return null;
    }

    private String getNamespace(String key) {
        if (key != null) {
            int indexOfNS = key.indexOf(mOptions.getNsSeparator());
            if (indexOfNS > 0) {
                String namespace = key.substring(0, indexOfNS);
                log("namespace found for key '%s': %s", key, namespace);
                return namespace;
            }
        }
        String defaultNameSpace = mOptions.getDefaultNamespace();
        if (defaultNameSpace == null) {
            Iterator<?> iterator = mRootObject.keys();
            if (iterator.hasNext()) {
                defaultNameSpace = String.valueOf(iterator.next());
                log("namespace taken from the first key available (it's now our default namespace): %s", defaultNameSpace);
                mOptions.setDefaultNamespace(defaultNameSpace);
            }
        } else {
            log("namespace found in default: %s", defaultNameSpace);
        }
        return defaultNameSpace;
    }

    public String t(String key) {
        return t(key, null);
    }

    /**
     * Using multiple keys (first found will be translated)
     * 
     * @param keys
     * @return
     */
    public String t(String... keys) {
        return t(keys, null);
    }

    public String t(String key, Operation operation) {
        String[] keys = { key };
        return t(keys, operation);
    }

    public String t(String[] keys, Operation operation) {
        String innerProcessValue = null;
        if (keys != null && keys.length > 0) {
            for (String key : keys) {
                innerProcessValue = innerProcessing(getValueRaw(key, operation));
                if (innerProcessValue != null) {
                    break;
                }
            }
        }
        if (operation instanceof Operation.PostOperation) {
            innerProcessValue = ((Operation.PostOperation) operation).postProcess(innerProcessValue);
        }
        return innerProcessValue;
    }

    public boolean existValue(String key) {
        return getValueRaw(key, null) != null;
    }

    private String getValueRaw(String key, Operation operation) {
        String namespace = getNamespace(key);
        if (namespace != null) {
            if (key.startsWith(namespace)) {
                key = key.substring(namespace.length() + 1); // +1 for the colon
            }
            if (operation instanceof Operation.PreOperation) {
                // it's the last key part
                key = ((Operation.PreOperation) operation).preProcess(key);
            }
            String[] splitKeys = splitKeyPath(key);
            if (splitKeys != null) {
                Object o = mRootObject.opt(namespace);
                for (int i = 0; i < splitKeys.length; i++) {
                    String splitKey = splitKeys[i];
                    if (o instanceof JSONObject) {
                        o = ((JSONObject) o).opt(splitKey);
                    } else {
                        o = null;
                        break;
                    }
                }
                if (o instanceof String) {
                    return (String) o;
                } else {
                    log(LogMode.WARNING, "impossible to found key '%s'", key);
                }
            }
        }
        return null;
    }

    private String innerProcessing(String raw) {
        // nesting
        String reusePrefix = mOptions.getReusePrefix();
        String reuseSuffix = mOptions.getReuseSuffix();
        if (raw != null && raw.length() > 0 && reusePrefix != null && reuseSuffix != null && reusePrefix.length() > 0 && reuseSuffix.length() > 0) {
            int indexOfPrefix = raw.indexOf(reusePrefix);
            if (indexOfPrefix > 0) {
                int indexOfSuffix = raw.indexOf(reuseSuffix, indexOfPrefix);
                if (indexOfSuffix > 0) {
                    // we've found a prefix and a suffix
                    String param = raw.substring(indexOfPrefix, indexOfSuffix + reuseSuffix.length());
                    String replacement = null;
                    String paramTrim = param.substring(reusePrefix.length(), indexOfSuffix - indexOfPrefix);
                    replacement = t(paramTrim);
                    if (replacement == null) {
                        replacement = "";
                    }
                    raw = raw.replace(param, replacement);
                }
            }
        }
        return raw;
    }
}