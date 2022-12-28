package com.tencent.soter.soterserver;

import android.os.Parcel;
import android.os.Parcelable;

public class SoterExportResult implements Parcelable {

    public static final Parcelable.Creator<SoterExportResult> CREATOR = new Parcelable.Creator<SoterExportResult>() {
        @Override
        public SoterExportResult createFromParcel(Parcel in) {
            return new SoterExportResult(in);
        }

        @Override
        public SoterExportResult[] newArray(int size) {
            return new SoterExportResult[size];
        }
    };
    public int resultCode;
    public byte[] exportData;
    public int exportDataLength;

    public SoterExportResult() {
    }

    public SoterExportResult(Parcel in) {
        resultCode = in.readInt();
        exportData = in.createByteArray();
        exportDataLength = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(resultCode);
        dest.writeByteArray(exportData);
        dest.writeInt(exportDataLength);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
