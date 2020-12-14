package org.parg.biglybt.plugins.maxshareratio;


import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.*;

import com.biglybt.core.CoreFactory;
import com.biglybt.core.config.*;
import com.biglybt.core.disk.DiskManagerPiece;
import com.biglybt.core.download.DownloadManagerState;
import com.biglybt.core.ipfilter.*;
import com.biglybt.core.peer.PEPeer;
import com.biglybt.core.peer.PEPeerManager;
import com.biglybt.core.peermanager.piecepicker.PiecePicker;
import com.biglybt.core.peermanager.piecepicker.PiecePriorityProvider;
import com.biglybt.core.peermanager.piecepicker.util.BitFlags;
import com.biglybt.core.util.Average;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SystemTime;
import com.biglybt.pif.*;
import com.biglybt.pif.download.*;
import com.biglybt.pif.logging.*;
import com.biglybt.pif.peers.*;
import com.biglybt.pif.torrent.*;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.menus.*;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.ui.tables.*;
import com.biglybt.pif.utils.*;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import com.biglybt.pifimpl.local.peers.PeerManagerImpl;



public class 
MaxShareRatioPlugin
	implements Plugin, DownloadManagerListener, DownloadPeerListener
{
	public static final int INITIAL_DELAY_MILLIS			= 60000;
	public static final int MIN_PIECE_MILLIS				= 60000;
	public static final int UP_IDLE_LIMIT_SECS_DEFAULT		= 120;
	public static final int UP_IDLE_LIMIT_SECS_INC			= 120;
	public static final int MIN_INTERESTED_PEERS			= 3;
	public static final int MIN_UPLOAD_SPEED				= 512;
	public static final int MAX_SEEDS_PER_TORRENT			= 20;
	public static final int MAX_UPLOAD_SLOTS				= 100;
	
	static{
		
		boolean stealth = System.getProperty( "upmax_stealth", null ) != null;
		
		System.out.println( "Stealth=" + stealth );
		
		COConfigurationManager.setParameter( "peercontrol.udp.probe.enable", stealth );
		COConfigurationManager.setParameter( "peercontrol.hide.piece", stealth );
	}
	
	private static volatile LocationProvider	country_provider;
	private static long							country_provider_last_check;

	private static final Object	country_key 	= new Object();
	private static final Object	net_key 		= new Object();

	private static LocationProvider
	getCountryProvider()
	{
		if ( country_provider != null ){

			if ( country_provider.isDestroyed()){

				country_provider 			= null;
				country_provider_last_check	= 0;
			}
		}

		if ( country_provider == null ){

			long	now = SystemTime.getMonotonousTime();

			if ( country_provider_last_check == 0 || now - country_provider_last_check > 20*1000 ){

				country_provider_last_check = now;

				java.util.List<LocationProvider> providers = CoreFactory.getSingleton().getPluginManager().getDefaultPluginInterface().getUtilities().getLocationProviders();

				for ( LocationProvider provider: providers ){

					if ( 	provider.hasCapabilities(
								LocationProvider.CAP_ISO3166_BY_IP |
								LocationProvider.CAP_COUNTY_BY_IP )){

						country_provider = provider;
					}
				}
			}
		}

		return( country_provider );
	}
	
	
	private PluginInterface		plugin_interface;
	private LoggerChannel		logger;
	private LocaleUtilities 	loc_utils;

	private TorrentAttribute	enabled_attribute;
	private TorrentAttribute	continue_when_complete_attribute;

	private Map<Download,Boolean>	download_map 		= new HashMap<Download, Boolean>();
	private Map<Download,Average>	download_uploads 	= new HashMap<Download, Average>();
	
	private volatile Set<String>		bad_ccs = null;
	
	public void 
	load(	
		PluginInterface 	_pi )
	{				
		try{
			
			IpFilterManagerFactory.getSingleton().getIPFilter().addExternalHandler(
				new IpFilterExternalHandler()
				{
					public boolean
					isBlocked(
						byte[]			torrent_hash,
						String			ip )
					{
						try{
							return( isBlocked( torrent_hash, InetAddress.getByName( ip )));
							
						}catch( Throwable e ){
														
							return( false );
						}
					}
					
					public boolean
					isBlocked(
						byte[]			torrent_hash,
						InetAddress		ip )
					{
						LocationProvider lp = getCountryProvider();
						
						if ( lp != null ){
							
							String cc = lp.getISO3166CodeForIP( ip );
				
							if ( bad_ccs == null ){
								
								System.out.println( "Blocking " + ip.getHostAddress() + " as not initialised" );
								
								return( true );
							}
							
							if ( bad_ccs.contains( cc )){
								
								logger.log( "Blocking " + ip.getHostAddress() + " as cc is " + cc );
								
								return( true );
							}
							
							// System.out.println( "Permitting " + ip.getHostAddress() + " as cc is " + cc );
						}
						
						return( false );
					}
				});
			
		}catch( Throwable e ){
			
			Debug.printStackTrace( e );
		}
	}
	
	public void 
	initialize(	
		PluginInterface 	_pi )
	{
		plugin_interface	= _pi;
		
		logger				= plugin_interface.getLogger().getTimeStampedChannel( "UpMaxer" ); 

		enabled_attribute					= plugin_interface.getTorrentManager().getPluginAttribute( "enabled" );
		continue_when_complete_attribute	= plugin_interface.getTorrentManager().getPluginAttribute( "continue_when_complete" );

		loc_utils = plugin_interface.getUtilities().getLocaleUtilities();

		loc_utils.integrateLocalisedMessageBundle( "org.parg.biglybt.plugins.maxshareratio.internat.Messages" );

		UIManager	ui_manager	= plugin_interface.getUIManager();
		
		TableManager	table_manager = ui_manager.getTableManager();

		final BasicPluginViewModel	view_model = ui_manager.createBasicPluginViewModel( "maxshareratio.name" );

		view_model.getActivity().setVisible( false );
		view_model.getProgress().setVisible( false );
		
		logger.addListener(
				new LoggerChannelListener()
				{
					public void
					messageLogged(
						int		type,
						String	content )
					{
						view_model.getLogArea().appendText( content + "\n" );
					}
					
					public void
					messageLogged(
						String		str,
						Throwable	error )
					{
						if ( str.length() > 0 ){
							view_model.getLogArea().appendText( str + "\n" );
						}
						
						StringWriter sw = new StringWriter();
						
						PrintWriter	pw = new PrintWriter( sw );
						
						error.printStackTrace( pw );
						
						pw.flush();
						
						view_model.getLogArea().appendText( sw.toString() + "\n" );
					}
				});		
		
		TableColumn	sms_status_column = 
			table_manager.createColumn(
					TableManager.TABLE_MYTORRENTS_INCOMPLETE,
					"maxshareratio.ui.label.column" );
		
		sms_status_column.setAlignment(TableColumn.ALIGN_LEAD);
		sms_status_column.setPosition(TableColumn.POSITION_LAST);
		sms_status_column.setRefreshInterval(TableColumn.INTERVAL_LIVE);
		sms_status_column.setType(TableColumn.TYPE_TEXT);
		
		sms_status_column.addCellRefreshListener(
			new TableCellRefreshListener()
			{
				public void 
				refresh(
					TableCell cell )
				{
					Download	dl = (Download)cell.getDataSource();
					
					String	text;
					
					if ( isMaxUpEnabled( dl )){
					
						text = getMessage( "maxshareratio.ui.label.enabled" );
						
					}else{
						
						text = getMessage( "maxshareratio.ui.label.disabled" );

					}
					
					cell.setText( text );
				}
			});
		
		table_manager.addColumn( sms_status_column );
		
		
		TableContextMenuItem	enable_menu = 
			table_manager.addContextMenuItem(  
				TableManager.TABLE_MYTORRENTS_INCOMPLETE,
				"maxshareratio.contextmenu.enable" );
		
		enable_menu.setStyle( MenuItem.STYLE_CHECK );
		
		enable_menu.setData( new Boolean( true ));
		
		enable_menu.addFillListener(
				new MenuItemFillListener()
				{
					public void
					menuWillBeShown(
						MenuItem	menu,
						Object 		target )
					{
						TableRow[]	rows = (TableRow[])target;
						
						boolean	all_enabled	 		= true;
						boolean	all_disabled		= true;
						
						for (int i=0;i<rows.length;i++){
							
							Download	download = (Download)rows[i].getDataSource();

							boolean	enabled = isMaxUpEnabled( download );
							
							if ( enabled ){
								
								all_disabled = false;
								
							}else{
								
								all_enabled = false;
							}
						}
						
						if ( all_enabled ){
							
							menu.setEnabled( true );
							
							menu.setData( new Boolean( true ));
							
						}else if ( all_disabled ){
							
							menu.setData( new Boolean( false ));

							menu.setEnabled( true );

						}else{
							
							menu.setEnabled( false );
						}
					}
				});
		
		enable_menu.addListener(
			new MenuItemListener()
			{
				public void
				selected(
					MenuItem	menu,
					Object 		target )
				{
					TableRow	row = (TableRow)target;
					
					Download	download = (Download)row.getDataSource();
					
					setMaxUpEnabled( 
						download, ((Boolean)menu.getData()).booleanValue());
				}
			});
		
		TableContextMenuItem	stop_when_complete_menu = 
			table_manager.addContextMenuItem(  
				TableManager.TABLE_MYTORRENTS_INCOMPLETE,
				"maxshareratio.swc.contextmenu.enable" );
		
		stop_when_complete_menu.setStyle( MenuItem.STYLE_CHECK );
		
		stop_when_complete_menu.setData( new Boolean( true ));
		
		MenuItemFillListener swc_fill_listener =
			new MenuItemFillListener()
			{
				public void
				menuWillBeShown(
					MenuItem	menu,
					Object 		target )
				{
					TableRow[]	rows = (TableRow[])target;
					
					boolean	all_enabled	 		= true;
					boolean	all_disabled		= true;
					
					for (int i=0;i<rows.length;i++){
						
						Download	download = (Download)rows[i].getDataSource();

						boolean	enabled = isMaxUpEnabled( download );
						
						if ( !enabled ){
							
							menu.setData( new Boolean( false ));
							
							all_disabled = all_enabled = false;
							
							break;
						}
						
						boolean	swc_enabled = isStopWhenComplete( download );
						
						if ( swc_enabled ){
							
							all_disabled = false;
							
						}else{
							
							all_enabled = false;
						}
					}
					
					if ( all_enabled ){
						
						menu.setEnabled( true );
						
						menu.setData( new Boolean( true ));
						
					}else if ( all_disabled ){
						
						menu.setData( new Boolean( false ));

						menu.setEnabled( true );

					}else{
						
						menu.setEnabled( false );
					}
				}
			};
		
		MenuItemListener swc_listener = 
			new MenuItemListener()
			{
				public void
				selected(
					MenuItem	menu,
					Object 		target )
				{
					TableRow	row = (TableRow)target;
					
					Download	download = (Download)row.getDataSource();
					
					setStopWhenComplete( 
						download, ((Boolean)menu.getData()).booleanValue());
				}
			};
		
		stop_when_complete_menu.addFillListener( swc_fill_listener );
		stop_when_complete_menu.addListener( swc_listener );

		TableContextMenuItem	stop_when_complete_menu2 = 
			table_manager.addContextMenuItem(  
				TableManager.TABLE_MYTORRENTS_COMPLETE,
				"maxshareratio.swc.contextmenu.enable" );
		
		stop_when_complete_menu2.setStyle( MenuItem.STYLE_CHECK );
		
		stop_when_complete_menu2.setData( new Boolean( true ));

		stop_when_complete_menu2.addFillListener( swc_fill_listener );
		stop_when_complete_menu2.addListener( swc_listener );

		BasicPluginConfigModel config_model = 
			ui_manager.createBasicPluginConfigModel( "maxshareratio.name" );

		view_model.setConfigSectionID( "maxshareratio.name" );
		
		final StringParameter bad_cc = config_model.addStringParameter2( "maxshareratio.bad_cc", "maxshareratio.bad_cc", "" );

		readBadCC( bad_cc.getValue());
		
		plugin_interface.getPluginconfig().addListener(
			new PluginConfigListener()
			{
				public void
				configSaved()
				{
					readBadCC(  bad_cc.getValue());
				}
			});
		
		plugin_interface.getDownloadManager().addListener( this );
		
		plugin_interface.getUtilities().createTimer( "stats", true ).addPeriodicEvent(
			1000,
			new UTTimerEventPerformer()
			{
				private int	ticks = 0;
				
				public void 
				perform(
					UTTimerEvent event )
				{
					synchronized( MaxShareRatioPlugin.this ){
						
						for ( Download download: download_map.keySet()){
							
							updateUploadAverage( download );
						}
					}
				
					ticks++;
					
					if ( ticks % 10 == 0 ){
					
						Download[] downloads = plugin_interface.getDownloadManager().getDownloads();
					
						for ( Download download: downloads ){
							
							boolean enabled = isMaxUpEnabled( download );
								
							if ( 	enabled && 
									download.getState() == Download.ST_SEEDING && 
									isStopWhenComplete( download )){
								
								if ( !download.isChecking()){
									
									try{
										log( download, "Stopping as stop-when-complete" );
										
										download.stop();
										
									}catch( Throwable e ){
										
										log( download, "Failed to stop download" );
									}
								}
							}
							
							if ( enabled ){
								
								PluginCoreUtils.unwrap( download ).getDownloadState().setLongParameter( DownloadManagerState.PARAM_MAX_UPLOADS, MAX_UPLOAD_SLOTS );
								
								PeerManager pm = download.getPeerManager();
								
								if ( pm != null ){
									
									int	connected_seeds = pm.getStats().getConnectedSeeds();

									Peer[] peers = pm.getPeers();
									
									Arrays.sort( 
										peers,
										new Comparator<Peer>()
										{
											public int 
											compare(
												Peer o1, 
												Peer o2) 
											{
												boolean c1 = o1.isChoked();
												boolean c2 = o2.isChoked();
												
												long x = o2.getStats().getTotalReceived() - o1.getStats().getTotalReceived();
												int	receive_diff;
												
												if ( x < 0 ){
													receive_diff 	= -1;
												}else if ( x > 0 ){
													receive_diff	= 1;
												}else{
													receive_diff	= 0;
												}
												
												if ( c1 && c2 ){
													
													return( receive_diff);
													
												}else if ( c1 ){
													
													return( -1 );
													
												}else if ( c2 ){
													
													return( 1 );
													
												}else{
													
													return( receive_diff );
												}
											}
										});
									
									for ( Peer peer: peers ){
								
										if ( connected_seeds < MAX_SEEDS_PER_TORRENT ){
											
											break;
										}
										
										if ( peer.isSeed()){
											
											pm.removePeer( peer );
											
											connected_seeds--;
										}
									}
								}
							}
						}
					}
				}
			});
	}
	
	protected void
	readBadCC(
		String	str )
	{
		Set<String> new_bad_cc = new HashSet<String>();
		
		String[] ccs = str.split( "," );
		
		String log_str = "";
		
		for ( String cc: ccs ){
			
			cc = cc.trim();
			
			if ( cc.length() > 0 ){
				
				new_bad_cc.add( cc );
				
				log_str += (log_str.length()==0?"":",") + cc;
			}
		}
		
		logger.log( "Bad CC contains " + new_bad_cc.size() + " entries: " + log_str );
		
		bad_ccs = new_bad_cc;
	}
	
	public void
	downloadAdded(
		Download	download )
	{
		if ( download.getTorrent() != null ){
			
			download.addPeerListener( this );
		}
	}
	
	public void
	downloadRemoved(
		Download	download )
	{
		
	}
	
	public void
	peerManagerAdded(
		final Download		download,
		PeerManager			peer_manager )
	{
		PeerManagerImpl	_pm = (PeerManagerImpl)peer_manager;
		
		final PEPeerManager pm = _pm.getDelegate();
		
		pm.getPiecePicker().addPriorityProvider(
			new PiecePriorityProvider(){
				private long	start_time	= SystemTime.getCurrentTime();
				private long[]	priorities 	= new long[(int)download.getTorrent().getPieceCount()];
				
				private int		current_piece = -1;
				private long	current_piece_done_time;
				
				private int		idle_secs_marker;
				private int		idle_secs_max		= UP_IDLE_LIMIT_SECS_DEFAULT;
				
				public long[]
	        	updatePriorities(
	        		PiecePicker		picker )
				{
					if ( !isMaxUpEnabled( download )){
						
						return( null );
					}
					
					DiskManagerPiece[]	pieces = pm.getDiskManager().getPieces();

					if ( current_piece != -1 ){
						
						if ( pieces[current_piece].isDone()){
							
							long	now = SystemTime.getCurrentTime();
							
							if ( current_piece_done_time == 0 ){
								
								log( download, "Piece " + current_piece + " ready for upload" );
								
								current_piece_done_time = now;
								
							}else if ( 	current_piece_done_time > now ||
										now - current_piece_done_time > MIN_PIECE_MILLIS ){
										
								int	up_idle_secs = pm.getStats().getTimeSinceLastDataSentInSeconds();
								
								if ( up_idle_secs > idle_secs_max ){
								
									idle_secs_max += UP_IDLE_LIMIT_SECS_INC * 2;
									
									log( download, "Increasing idle limit to " + idle_secs_max );
									
									int	num_interested = 0;
									
									List<PEPeer>	peers = (List<PEPeer>)pm.getPeers();
									
									for ( PEPeer peer: peers ){
										
										BitFlags	flags = peer.getAvailable();
										
										if ( flags != null && !flags.flags[current_piece]){
											
											num_interested++;
										}
									}
									
									if ( num_interested < MIN_INTERESTED_PEERS ){
									
										log( download, "Abandoning piece " + current_piece + ", upload too slow and insufficient interested peers" );
									
										current_piece = -1;
									}
								}else{
									
									if ( up_idle_secs < UP_IDLE_LIMIT_SECS_DEFAULT ){
										
										if ( idle_secs_max > UP_IDLE_LIMIT_SECS_DEFAULT ){
										
											idle_secs_max	= UP_IDLE_LIMIT_SECS_DEFAULT;
											
											log( download, "Decreasing idle limit to " + idle_secs_max );
										}
									}
								}
							}
						}
					}
					
					if ( current_piece == -1 ){
					
						Arrays.fill( priorities, Integer.MIN_VALUE );
						
						long	now = SystemTime.getCurrentTime();
					
						if ( now < start_time || now - start_time > INITIAL_DELAY_MILLIS ){
								
							int[]	avails 		= picker.getAvailability();
							int		min_avail 	= Integer.MAX_VALUE;
							
							List<Integer>	min_pieces 			= null;
							List<Integer>	min_pieces_unchoked = null;
							
							for (int i=0;i<avails.length;i++){
							
								DiskManagerPiece	piece = pieces[i];
								
								int	avail = avails[i];
								
								if ( 	avail > 0 &&		// no point in selecting a piece that isn't available
										avail <= min_avail && 
										piece.isNeeded() && 
										!piece.isDone() &&
										pm.getPiece( i ) == null ){
										
										// only download a piece if at least 3 other peers
										// need it
									
									int	num_interested	= 0;
									int	num_unchoked	= 0;
									
									List<PEPeer>	peers = (List<PEPeer>)pm.getPeers();
									
									for ( PEPeer peer: peers ){
										
										BitFlags	flags = peer.getAvailable();
										
										if ( flags != null && !flags.flags[i]){
											
											num_interested++;
										}
										
										if ( !peer.isChokingMe()){
											
											num_unchoked++;
										}
									}
									
									if ( num_interested >= MIN_INTERESTED_PEERS ){
										
										if ( avail < min_avail || min_pieces == null ){
											
											min_pieces 			= new ArrayList<Integer>();
											min_pieces_unchoked	= new ArrayList<Integer>();		
											
											min_avail = avail;
										}
									
										min_pieces.add( i );
										
										if ( num_unchoked > 0 ){
											
											min_pieces_unchoked.add(i);
										}
									}
								}
							}
							
							if ( min_pieces != null){
								
								if ( min_pieces_unchoked.size() == 0 ){
									
									current_piece = min_pieces.get( new Random().nextInt( min_pieces.size()));
									
								}else{
									
									current_piece = min_pieces_unchoked.get( new Random().nextInt( min_pieces_unchoked.size()));
								}
								
								priorities[current_piece]	= 100000;
								current_piece_done_time		= 0;
								
								log( download, "Selecting new piece " + current_piece + ", availability=" + min_avail );
							}
						}
					}
						
					return( priorities );
				}
			});
		
		peer_manager.addListener(
			new PeerManagerListener2() 
			{	
				public void 
				eventOccurred(
					PeerManagerEvent event )
				{	
					if ( !isMaxUpEnabled( download )){
						
						return;
					}
					
					try{
						final PeerManager	pm = event.getPeerManager();
						
						final Peer peer = event.getPeer();
						
						if ( peer != null && pm != null){
							
							int	connected_seeds = pm.getStats().getConnectedSeeds();
							
							if ( connected_seeds < MAX_SEEDS_PER_TORRENT ){
								
								int	type = event.getType();
								
								if ( type == PeerManagerEvent.ET_PEER_ADDED ){
						
									if ( peer.isSeed()){
																			
										pm.removePeer( peer );
									}
								}
							}
						}
					}catch( Throwable e ){
						
					}
				}
			});
			
	}
	
	public void
	peerManagerRemoved(
		Download		download,
		PeerManager		peer_manager )
	{
	}
	
	protected synchronized boolean
	isMaxUpEnabled(
		Download		download )
	{
		Boolean	state = download_map.get( download );
		
		if ( state == null ){
		
			state = new Boolean( download.getBooleanAttribute( enabled_attribute ));
		}
		
		download_map.put( download, state );
		
		return( state );
	}
	
	protected synchronized void
	updateUploadAverage(
		Download		download )
	{	
		Average	average = download_uploads.get( download );
		
		if ( average == null ){
			
			average = Average.getInstance( 1000, 60 );
			
			download_uploads.put( download, average );
		}
		
		average.addValue( download.getStats().getUploadAverage());
	}
	
	protected synchronized long
	getUploadAverage(
		Download		download )
	{
		Average	average = download_uploads.get( download );
		
		if ( average == null ){

			return( 0 );
		}
		
		return( average.getAverage());
	}
	
	protected synchronized void
	setMaxUpEnabled(
		Download		download,
		boolean			enabled )
	{
		download_map.put( download, enabled );
		
		download.setBooleanAttribute( enabled_attribute, enabled );
	}
	
	protected boolean
	isStopWhenComplete(
		Download	download )
	{
		return( !download.getBooleanAttribute( continue_when_complete_attribute ));
	}
	
	protected void
	setStopWhenComplete(
		Download	download,
		boolean		stop )
	{
		download.setBooleanAttribute( continue_when_complete_attribute, !stop );
	}
	
	public String
	getMessage(
		String	resource )
	{
		return( loc_utils.getLocalisedMessageText( resource ));
	}
	
	protected void
	log(
		Download	dl,
		String		str )
	{
		logger.log( dl.getName() + ": " + str );
	}
}
