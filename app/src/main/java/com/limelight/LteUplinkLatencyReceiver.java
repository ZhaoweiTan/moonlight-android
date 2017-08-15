package com.limelight;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Created by yuanjieli on 8/14/17.
 */
public class LteUplinkLatencyReceiver extends BroadcastReceiver{
    @Override
    public void onReceive
            (Context context, Intent intent) {
        // react to the event
        if(intent.getAction().equals("android.appwidget.action.APPWIDGET_ENABLED")) {
            //TODO: ???
        }
        else if(intent.getAction().equals("MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN")){

            Log.i("Yuanjie","MobileInsight.UlLatBreakdownAnalyzer.UL_LAT_BREAKDOWN");

        }
        else if(intent.getAction().equals("MobileInsight.RrcSrAnalyzer.RRC_SR")){

            Log.i("Yuanjie","MobileInsight.RrcSrAnalyzer.RRC_SR");

        }
    }

}
