/*
 * Wapdroid - Android Location based Wifi Manager
 * Copyright (C) 2009 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */

package com.piusvelte.wapdroid.providers;

import static com.piusvelte.wapdroid.Wapdroid.AUTHORITY;
//import static com.piusvelte.wapdroid.Wapdroid.TAG;

import java.util.HashMap;

import com.piusvelte.wapdroid.Wapdroid.Cells;
import com.piusvelte.wapdroid.Wapdroid.Locations;
import com.piusvelte.wapdroid.Wapdroid.Networks;
import com.piusvelte.wapdroid.Wapdroid.Pairs;
import com.piusvelte.wapdroid.Wapdroid.Ranges;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;

public class WapdroidContentProvider extends ContentProvider {
	private static final String DATABASE_NAME = "wapdroid";
	private static final int DATABASE_VERSION = 7;
	public static final String TABLE_NETWORKS = "networks";
	public static final String TABLE_CELLS = "cells";
	public static final String TABLE_PAIRS = "pairs";
	public static final String TABLE_LOCATIONS = "locations";
	public static final String VIEW_RANGES = "ranges";
	private static final int NETWORKS = 1;
	private static final int CELLS = 2;
	private static final int PAIRS = 3;
	private static final int LOCATIONS = 4;
	private static final int RANGES = 5;
	private static HashMap<String, String> networksProjectionMap;
	private static HashMap<String, String> cellsProjectionMap;
	private static HashMap<String, String> pairsProjectionMap;
	private static HashMap<String, String> locationsProjectionMap;
	private static HashMap<String, String> rangesProjectionMap;
	public static final int UNKNOWN_CID = -1;
	public static final int UNKNOWN_RSSI = 99;

	private static UriMatcher sUriMatcher;

	private static class DatabaseHelper extends SQLiteOpenHelper {

		public DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			db.execSQL("create table if not exists " + TABLE_NETWORKS + " ("
					+ Networks._ID + " integer primary key autoincrement, "
					+ Networks.SSID + " text not null, "
					+ Networks.BSSID + " text not null);");
			db.execSQL("create table if not exists " + TABLE_CELLS + " ("
					+ Cells._ID + " integer primary key autoincrement, "
					+ Cells.CID + " integer, location integer);");
			db.execSQL("create table if not exists " + TABLE_PAIRS + " ("
					+ Pairs._ID + " integer primary key autoincrement, "
					+ Pairs.CELL + " integer, "
					+ Pairs.NETWORK + " integer, "
					+ Pairs.RSSI_MIN + " integer, "
					+ Pairs.RSSI_MAX + " integer);");
			db.execSQL("create table if not exists " + TABLE_LOCATIONS + " ("
					+ Locations._ID + " integer primary key autoincrement, "
					+ Locations.LAC + " integer);");
			db.execSQL("create view if not exists " + VIEW_RANGES + " as select "
					+ TABLE_PAIRS + "." + Pairs._ID + " as " + Ranges._ID
					+ "," + Pairs.RSSI_MAX
					+ "," + Pairs.RSSI_MIN
					+ "," + Cells.CID
					+ "," + Locations.LAC
					+ "," + Cells.LOCATION
					+ "," + Networks.SSID
					+ "," + Networks.BSSID
					+ " from " + TABLE_PAIRS
					+ " left join " + TABLE_CELLS + " on " + TABLE_CELLS + "." + Cells._ID + "=" + Pairs.CELL
					+ " left join " + TABLE_LOCATIONS + " on " + TABLE_LOCATIONS + "." + Locations._ID + "=" + Cells.LOCATION
					+ " left join " + TABLE_NETWORKS + " on " + TABLE_NETWORKS + "." + Networks._ID + "=" + Pairs.NETWORK + ";");
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			if (oldVersion < 2) {
				// add BSSID
				db.execSQL("drop table if exists " + TABLE_NETWORKS + "_bkp;");
				db.execSQL("create temporary table " + TABLE_NETWORKS + "_bkp as select * from " + TABLE_NETWORKS + ";");
				db.execSQL("drop table if exists " + TABLE_NETWORKS + ";");
				db.execSQL("create table if not exists " + TABLE_NETWORKS + " (_id  integer primary key autoincrement, "
						+ Networks.SSID + " text not null, "
						+ Networks.BSSID + " text not null);");
				db.execSQL("insert into " + TABLE_NETWORKS + " select " + Networks._ID + ", " + Networks.SSID + ", \"\"" + " from " + TABLE_NETWORKS + "_bkp;");
				db.execSQL("drop table if exists " + TABLE_NETWORKS + "_bkp;");
			}
			if (oldVersion < 3) {
				// add locations
				db.execSQL("create table if not exists " + TABLE_LOCATIONS + " (_id  integer primary key autoincrement, "
						+ Locations.LAC + " integer);");
				// first backup cells to create pairs
				db.execSQL("drop table if exists " + TABLE_CELLS + "_bkp;");
				db.execSQL("create temporary table " + TABLE_CELLS + "_bkp as select * from " + TABLE_CELLS + ";");
				// update cells, dropping network column, making unique
				db.execSQL("drop table if exists " + TABLE_CELLS + ";");
				db.execSQL("create table if not exists " + TABLE_CELLS + " (_id  integer primary key autoincrement, " + Cells.CID + " integer, location integer);");
				db.execSQL("insert into " + TABLE_CELLS + " (" + Cells.CID + ", " + Cells.LOCATION
						+ ") select " + Cells.CID + ", " + UNKNOWN_CID + " from " + TABLE_CELLS + "_bkp group by " + Cells.CID + ";");
				// create pairs
				db.execSQL("create table if not exists " + TABLE_PAIRS + " (_id  integer primary key autoincrement, cell integer, network integer, " + Pairs.RSSI_MIN + " integer, " + Pairs.RSSI_MAX + " integer);");
				db.execSQL("insert into " + TABLE_PAIRS
						+ " (" + Pairs.CELL + ", " + Pairs.NETWORK + ", " + Pairs.RSSI_MIN + ", " + Pairs.RSSI_MAX
						+ ") select " + TABLE_CELLS + "." + Pairs._ID + ", " + TABLE_CELLS + "_bkp." + Pairs.NETWORK + ", " + UNKNOWN_RSSI + ", " + UNKNOWN_RSSI
						+ " from " + TABLE_CELLS + "_bkp"
						+ " left join " + TABLE_CELLS + " on " + TABLE_CELLS + "_bkp." + Cells.CID + "=" + TABLE_CELLS + "." + Cells.CID + ";");
				db.execSQL("drop table if exists " + TABLE_CELLS + "_bkp;");	
			}
			if (oldVersion < 4) {
				// clean lac=0 locations
				Cursor locations = db.rawQuery("select " + Locations._ID + " from " + TABLE_LOCATIONS + " where " + Locations.LAC + "=0", null);
				if (locations.getCount() > 0) {
					locations.moveToFirst();
					int index = locations.getColumnIndex(Locations._ID);
					while (!locations.isAfterLast()) {
						int location = locations.getInt(index);
						// clean pairs
						db.execSQL("delete from " + TABLE_PAIRS + " where " + Pairs._ID + " in (select " + TABLE_PAIRS + "." + Pairs._ID + " as " + Pairs._ID + " from " + TABLE_PAIRS
								+ " left join " + TABLE_CELLS + " on " + Pairs.CELL + "=" + TABLE_CELLS + "." + Cells._ID
								+ " where " + Cells.LOCATION + "=" + location + ");");
						// clean cells
						db.execSQL("delete from " + TABLE_CELLS + " where " + Cells.LOCATION + "=" + location + ";");
						locations.moveToNext();
					}
					// clean locations
					db.execSQL("delete from " + TABLE_LOCATIONS + " where " + Locations.LAC + "=0;");
				}			
			}
			if (oldVersion < 5) {
				// fix bad rssi values
				db.execSQL("update " + TABLE_PAIRS + " set " + Pairs.RSSI_MIN + "=-1*" + Pairs.RSSI_MIN + " where " + Pairs.RSSI_MIN + " >0 and " + Pairs.RSSI_MIN + " !=" + UNKNOWN_RSSI + ";");
				db.execSQL("update " + TABLE_PAIRS + " set " + Pairs.RSSI_MAX + "=-1*" + Pairs.RSSI_MAX + " where " + Pairs.RSSI_MAX + " >0 and " + Pairs.RSSI_MAX + " !=" + UNKNOWN_RSSI + ";");			
			}
			if (oldVersion < 6) {
				// revert incorrect unknown rssi's
				db.execSQL("update " + TABLE_PAIRS + " set " + Pairs.RSSI_MIN + "=99," + Pairs.RSSI_MAX + "=99 where " + Pairs.RSSI_MAX + "<" + Pairs.RSSI_MIN + " and RSSI_max=-85;");			
			}
			if (oldVersion < 7) {
				db.execSQL("create view if not exists " + VIEW_RANGES + " as select "
						+ TABLE_PAIRS + "." + Pairs._ID + " as " + Ranges._ID
						+ "," + Pairs.RSSI_MAX
						+ "," + Pairs.RSSI_MIN
						+ "," + Cells.CID
						+ "," + Locations.LAC
						+ "," + Cells.LOCATION
						+ "," + Pairs.NETWORK
						+ "," + Networks.SSID
						+ "," + Networks.BSSID
						+ " from " + TABLE_PAIRS
						+ " left join " + TABLE_CELLS + " on " + TABLE_CELLS + "." + Cells._ID + "=" + Pairs.CELL
						+ " left join " + TABLE_LOCATIONS + " on " + TABLE_LOCATIONS + "." + Locations._ID + "=" + Cells.LOCATION
						+ " left join " + TABLE_NETWORKS + " on " + TABLE_NETWORKS + "." + Networks._ID + "=" + Pairs.NETWORK + ";");
			}
		}		
	}

	private DatabaseHelper mOpenHelper;

	@Override
	public boolean onCreate() {
		mOpenHelper = new DatabaseHelper(getContext());
		return true;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		SQLiteDatabase db = mOpenHelper.getWritableDatabase();
		switch (sUriMatcher.match(uri)) {
		case NETWORKS:
			return db.delete(TABLE_NETWORKS, selection, selectionArgs);
		case CELLS:
			return db.delete(TABLE_CELLS, selection, selectionArgs);
		case PAIRS:
			return db.delete(TABLE_PAIRS, selection, selectionArgs);
		case LOCATIONS:
			return db.delete(TABLE_LOCATIONS, selection, selectionArgs);
		}
		return 0;
	}

	@Override
	public String getType(Uri uri) {
		switch (sUriMatcher.match(uri)) {
		case NETWORKS:
			return Networks.CONTENT_TYPE;
		case CELLS:
			return Cells.CONTENT_TYPE;
		case PAIRS:
			return Pairs.CONTENT_TYPE;
		case LOCATIONS:
			return Locations.CONTENT_TYPE;
		case RANGES:
			return Ranges.CONTENT_TYPE;
		}
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		SQLiteDatabase db = mOpenHelper.getWritableDatabase();
		long rowId = 0;
		switch (sUriMatcher.match(uri)) {
		case NETWORKS:
			rowId = db.insert(TABLE_NETWORKS, Networks.SSID, values);
			if (rowId > 0) {
				Uri networkUri = ContentUris.withAppendedId(Networks.CONTENT_URI, rowId);
				getContext().getContentResolver().notifyChange(networkUri, null);
				return networkUri;
			} else return null;
		case CELLS:
			rowId = db.insert(TABLE_CELLS, Cells.CID, values);
			if (rowId > 0) {
				Uri cellUri = ContentUris.withAppendedId(Cells.CONTENT_URI, rowId);
				getContext().getContentResolver().notifyChange(cellUri, null);
				return cellUri;
			} else return null;
		case PAIRS:
			rowId = db.insert(TABLE_PAIRS, Pairs.CELL, values);
			if (rowId > 0) {
				Uri pairUri = ContentUris.withAppendedId(Pairs.CONTENT_URI, rowId);
				getContext().getContentResolver().notifyChange(pairUri, null);
				return pairUri;
			} else return null;
		case LOCATIONS:
			rowId = db.insert(TABLE_LOCATIONS, Locations.LAC, values);
			if (rowId > 0) {
				Uri locationUri = ContentUris.withAppendedId(Networks.CONTENT_URI, rowId);
				getContext().getContentResolver().notifyChange(locationUri, null);
				return locationUri;
			} else return null;
		}
		return null;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
			String[] selectionArgs, String sortOrder) {
		SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
		switch (sUriMatcher.match(uri)) {
		case NETWORKS:
			qb.setTables(TABLE_NETWORKS);
			qb.setProjectionMap(networksProjectionMap);
			break;
		case CELLS:
			qb.setTables(TABLE_CELLS);
			qb.setProjectionMap(cellsProjectionMap);
			break;
		case PAIRS:
			qb.setTables(TABLE_PAIRS);
			qb.setProjectionMap(pairsProjectionMap);
			break;
		case LOCATIONS:
			qb.setTables(TABLE_LOCATIONS);
			qb.setProjectionMap(locationsProjectionMap);
			break;
		case RANGES:
			qb.setTables(VIEW_RANGES);
			qb.setProjectionMap(rangesProjectionMap);
			break;
		}
		SQLiteDatabase db = mOpenHelper.getReadableDatabase();
		Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);
		c.setNotificationUri(getContext().getContentResolver(), uri);
		return c;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
			String[] selectionArgs) {
		SQLiteDatabase db = mOpenHelper.getWritableDatabase();
		int count = 0;
		switch (sUriMatcher.match(uri)) {
		case NETWORKS:
			count = db.update(TABLE_NETWORKS, values, selection, selectionArgs);
			break;
		case CELLS:
			count = db.update(TABLE_CELLS, values, selection, selectionArgs);
			break;
		case PAIRS:
			count = db.update(TABLE_PAIRS, values, selection, selectionArgs);
			break;
		case LOCATIONS:
			count = db.update(TABLE_LOCATIONS, values, selection, selectionArgs);
			break;	
		}
		getContext().getContentResolver().notifyChange(uri, null);
		return count;
	}

	static {
		sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
		sUriMatcher.addURI(AUTHORITY, TABLE_NETWORKS, NETWORKS);
		sUriMatcher.addURI(AUTHORITY, TABLE_CELLS, CELLS);
		sUriMatcher.addURI(AUTHORITY, TABLE_PAIRS, PAIRS);
		sUriMatcher.addURI(AUTHORITY, TABLE_LOCATIONS, LOCATIONS);

		networksProjectionMap = new HashMap<String, String>();
		networksProjectionMap.put(Networks._ID, Networks._ID);
		networksProjectionMap.put(Networks.SSID, Networks.SSID);
		networksProjectionMap.put(Networks.BSSID, Networks.BSSID);

		cellsProjectionMap = new HashMap<String, String>();
		cellsProjectionMap.put(Cells._ID, Cells._ID);
		cellsProjectionMap.put(Cells.CID, Cells.CID);
		cellsProjectionMap.put(Cells.LOCATION, Cells.LOCATION);

		pairsProjectionMap = new HashMap<String, String>();
		pairsProjectionMap.put(Pairs._ID, Pairs._ID);
		pairsProjectionMap.put(Pairs.CELL, Pairs.CELL);
		pairsProjectionMap.put(Pairs.NETWORK, Pairs.NETWORK);
		pairsProjectionMap.put(Pairs.RSSI_MIN, Pairs.RSSI_MIN);
		pairsProjectionMap.put(Pairs.RSSI_MAX, Pairs.RSSI_MAX);

		locationsProjectionMap = new HashMap<String, String>();
		locationsProjectionMap.put(Locations._ID, Locations._ID);
		locationsProjectionMap.put(Locations.LAC, Locations.LAC);

		rangesProjectionMap = new HashMap<String, String>();
		rangesProjectionMap.put(Ranges._ID, Ranges._ID);
		rangesProjectionMap.put(Ranges.CID, Ranges.CID);
		rangesProjectionMap.put(Ranges.LAC, Ranges.LAC);
		rangesProjectionMap.put(Ranges.RSSI_MAX, Ranges.RSSI_MAX);
		rangesProjectionMap.put(Ranges.RSSI_MIN, Ranges.RSSI_MIN);
		rangesProjectionMap.put(Ranges.LOCATION, Ranges.LOCATION);
		rangesProjectionMap.put(Ranges.NETWORK, Ranges.NETWORK);
		rangesProjectionMap.put(Ranges.SSID, Ranges.SSID);
		rangesProjectionMap.put(Ranges.BSSID, Ranges.BSSID);
	}

}
