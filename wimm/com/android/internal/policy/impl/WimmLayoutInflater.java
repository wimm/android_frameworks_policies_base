package com.android.internal.policy.impl;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.LayoutInflater;

public class WimmLayoutInflater extends LayoutInflater {
    private static final String[] sClassPrefixList = {
        "android.widget.",
        "android.webkit.",
        "com.wimm."
    };
    
    /**
     * Instead of instantiating directly, you should retrieve an instance
     * through {@link Context#getSystemService}
     * 
     * @param context The Context in which in which to find resources and other
     *                application-specific things.
     * 
     * @see Context#getSystemService
     */
    public WimmLayoutInflater(Context context) {
        super(context);
    }
    
    protected WimmLayoutInflater(LayoutInflater original, Context newContext) {
        super(original, newContext);
    }
    
    /** Override onCreateView to instantiate names that correspond to the
        widgets known to the Widget factory. If we don't find a match,
        call through to our super class.
    */
    @Override protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {
        for (String prefix : sClassPrefixList) {
            try {
                View view = createView(name, prefix, attrs);
                if (view != null) {
                    return view;
                }
            } catch (ClassNotFoundException e) {
                // In this case we want to let the base class take a crack
                // at it.
            }
        }

        return super.onCreateView(name, attrs);
    }
    
    public LayoutInflater cloneInContext(Context newContext) {
        return new WimmLayoutInflater(this, newContext);
    }
}

