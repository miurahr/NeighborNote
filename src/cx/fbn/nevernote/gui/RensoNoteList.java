/*
 * This file is part of NeighborNote
 * Copyright 2013 Yuki Takahashi
 * 
 * This file may be licensed under the terms of of the
 * GNU General Public License Version 2 (the ``GPL'').
 *
 * Software distributed under the License is distributed
 * on an ``AS IS'' basis, WITHOUT WARRANTY OF ANY KIND, either
 * express or implied. See the GPL for the specific language
 * governing rights and limitations.
 *
 * You should have received a copy of the GPL along with this
 * program. If not, go to http://www.gnu.org/licenses/gpl.html
 * or write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
*/

package cx.fbn.nevernote.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.evernote.edam.type.Note;
import com.trolltech.qt.QThread;
import com.trolltech.qt.core.QByteArray;
import com.trolltech.qt.core.QFile;
import com.trolltech.qt.core.QSize;
import com.trolltech.qt.core.Qt.MouseButton;
import com.trolltech.qt.gui.QAction;
import com.trolltech.qt.gui.QApplication;
import com.trolltech.qt.gui.QContextMenuEvent;
import com.trolltech.qt.gui.QListWidget;
import com.trolltech.qt.gui.QListWidgetItem;
import com.trolltech.qt.gui.QMenu;

import cx.fbn.nevernote.Global;
import cx.fbn.nevernote.NeverNote;
import cx.fbn.nevernote.sql.DatabaseConnection;
import cx.fbn.nevernote.threads.CounterRunner;
import cx.fbn.nevernote.threads.ENRelatedNotesRunner;
import cx.fbn.nevernote.threads.ENThumbnailRunner;
import cx.fbn.nevernote.threads.SyncRunner;
import cx.fbn.nevernote.utilities.ApplicationLogger;
import cx.fbn.nevernote.utilities.Pair;

public class RensoNoteList extends QListWidget {
	private final DatabaseConnection conn;
	private final ApplicationLogger logger;
	private final HashMap<QListWidgetItem, String> rensoNoteListItems;
	private final HashMap<String, RensoNoteListItem> rensoNoteListTrueItems;
	private String rensoNotePressedItemGuid;
	private final QAction openNewTabAction;
	private final QAction starAction;
	private final QAction unstarAction;
	private final QAction excludeNoteAction;
	private final NeverNote parent;
	private final QMenu menu;
	private HashMap<String, Integer> mergedHistory;				// マージされた操作履歴
	private final SyncRunner syncRunner;
	private final ENRelatedNotesRunner enRelatedNotesRunner;
	private final QThread enRelatedNotesThread;
	private final HashMap<String, List<String>> enRelatedNotesCache;	// Evernote関連ノートのキャッシュ<guid, 関連ノートリスト>
	private final ENThumbnailRunner enThumbnailRunner;
	private final QThread enThumbnailThread;
	private String guid;

	public RensoNoteList(DatabaseConnection c, NeverNote p, SyncRunner syncRunner, ApplicationLogger logger) {
		this.logger = logger;
		this.logger.log(this.logger.HIGH, "Setting up rensoNoteList");

		this.conn = c;
		this.parent = p;
		this.syncRunner = syncRunner;
		
		this.guid = new String();
		mergedHistory = new HashMap<String, Integer>();
		enRelatedNotesCache = new HashMap<String, List<String>>();
		this.enRelatedNotesRunner = new ENRelatedNotesRunner(this.syncRunner, "enRelatedNotesRunner.log");
		this.enRelatedNotesRunner.enRelatedNotesSignal.getENRelatedNotesFinished.connect(this, "enRelatedNotesComplete()");
		this.enRelatedNotesRunner.limitSignal.rateLimitReached.connect(parent, "informRateLimit(Integer)");
		this.enRelatedNotesThread = new QThread(enRelatedNotesRunner, "ENRelatedNotes Thread");
		this.getEnRelatedNotesThread().start();
		
		this.enThumbnailRunner = new ENThumbnailRunner("enThumbnailRunner.log", CounterRunner.NOTEBOOK, 
					Global.getDatabaseUrl(), Global.getIndexDatabaseUrl(), Global.getResourceDatabaseUrl(), Global.getBehaviorDatabaseUrl(),
					Global.getDatabaseUserid(), Global.getDatabaseUserPassword(), Global.cipherPassword);
		this.enThumbnailRunner.enThumbnailSignal.getENThumbnailFinished.connect(this, "enThumbnailComplete(String)");
		this.enThumbnailRunner.limitSignal.rateLimitReached.connect(parent, "informRateLimit(Integer)");
		this.enThumbnailThread = new QThread(enThumbnailRunner, "ENThumbnail Thread");
		this.enThumbnailThread.start();
		
		rensoNoteListItems = new HashMap<QListWidgetItem, String>();
		rensoNoteListTrueItems = new HashMap<String, RensoNoteListItem>();
		
		this.itemPressed.connect(this, "rensoNoteItemPressed(QListWidgetItem)");
		
		// コンテキストメニュー作成
		menu = new QMenu(this);
		// 新しいタブで開くアクション生成
		openNewTabAction = new QAction(tr("Open in New Tab"), this);
		openNewTabAction.setToolTip(tr("Open this note in new tab"));
		openNewTabAction.triggered.connect(parent, "openNewTabFromRNL()");
		// スターをつけるアクション生成
		starAction = new QAction(tr("Add Star"), this);
		starAction.setToolTip(tr("Add Star to this item"));
		starAction.triggered.connect(parent, "starNote()");
		// スターを外すアクション生成
		unstarAction = new QAction(tr("Remove Star"), this);
		unstarAction.setToolTip(tr("Remove Star from this item"));
		unstarAction.triggered.connect(parent, "unstarNote()");
		// このノートを除外するアクション生成
		excludeNoteAction = new QAction(tr("Exclude"), this);
		excludeNoteAction.setToolTip(tr("Exclude this note from RensoNoteList"));
		excludeNoteAction.triggered.connect(parent, "excludeNote()");
		// コンテキストメニューに登録
		menu.addAction(openNewTabAction);
		menu.addAction(excludeNoteAction);
		menu.aboutToHide.connect(this, "contextMenuHidden()");
		
		this.logger.log(this.logger.HIGH, "rensoNoteList setup complete");
	}
	
	// オーバーロード
	// 現在開いているノートの連想ノートリストをリフレッシュ
	public void refreshRensoNoteList() {
		refreshRensoNoteList(guid);
	}

	// 連想ノートリストをリフレッシュ
	public void refreshRensoNoteList(String guid) {
		logger.log(logger.HIGH, "Entering RensoNoteList.refreshRensoNoteList guid = " + guid);

		this.clear();
		rensoNoteListItems.clear();
		rensoNoteListTrueItems.clear();
		mergedHistory = new HashMap<String, Integer>();

		if (!this.isEnabled()) {
			return;
		}
		if (guid == null || guid.equals("")) {
			return;
		}
		
		this.guid = guid;
		// すでにEvernote関連ノートがキャッシュされているか確認
		boolean isCached;
		isCached = enRelatedNotesCache.containsKey(guid);
		if (!isCached) {	// キャッシュ無し
			// Evernoteの関連ノートを別スレッドで取得させる
			enRelatedNotesRunner.addGuid(guid);
		} else {			// キャッシュ有り
			List<String> relatedNoteGuids = enRelatedNotesCache.get(guid);
			addENRelatedNotes(relatedNoteGuids);
		}
		
		calculateHistory(guid);
		repaintRensoNoteList(false);

		logger.log(logger.HIGH, "Leaving RensoNoteList.refreshRensoNoteList");
	}
	
	// 操作履歴をデータベースから取得してノートごとの関連度を算出、その後mergedHistoryに追加
	private void calculateHistory(String guid) {
		logger.log(logger.EXTREME, "Entering RensoNoteList.calculateHistory guid = " + guid);
		
		// browseHistory<guid, 回数（ポイント）>
		HashMap<String, Integer> browseHistory = conn.getHistoryTable().getBehaviorHistory("browse", guid);
		addWeight(browseHistory, Global.getBrowseWeight());
		mergedHistory = mergeHistory(filterHistory(browseHistory), mergedHistory);
		
		// copy&pasteHistory<guid, 回数（ポイント）>
		HashMap<String, Integer> copyAndPasteHistory = conn.getHistoryTable().getBehaviorHistory("copy & paste", guid);
		addWeight(copyAndPasteHistory, Global.getCopyPasteWeight());
		mergedHistory = mergeHistory(filterHistory(copyAndPasteHistory), mergedHistory);
		
		// addNewNoteHistory<guid, 回数（ポイント）>
		HashMap<String, Integer> addNewNoteHistory = conn.getHistoryTable().getBehaviorHistory("addNewNote", guid);
		addWeight(addNewNoteHistory, Global.getAddNewNoteWeight());
		mergedHistory = mergeHistory(filterHistory(addNewNoteHistory), mergedHistory);
		
		// rensoItemClickHistory<guid, 回数（ポイント）>
		HashMap<String, Integer> rensoItemClickHistory = conn.getHistoryTable().getBehaviorHistory("rensoItemClick", guid);
		addWeight(rensoItemClickHistory, Global.getRensoItemClickWeight());
		mergedHistory = mergeHistory(filterHistory(rensoItemClickHistory), mergedHistory);
		
		// sameTagHistory<guid, 回数（ポイント）>
		HashMap<String, Integer> sameTagHistory = conn.getHistoryTable().getBehaviorHistory("sameTag", guid);
		addWeight(sameTagHistory, Global.getSameTagWeight());
		mergedHistory = mergeHistory(filterHistory(sameTagHistory), mergedHistory);
		
		// sameNotebookNoteHistory<guid, 回数（ポイント）>
		HashMap<String, Integer> sameNotebookHistory = conn.getHistoryTable().getBehaviorHistory("sameNotebook", guid);
		addWeight(sameNotebookHistory, Global.getSameNotebookWeight());
		mergedHistory = mergeHistory(filterHistory(sameNotebookHistory), mergedHistory);
		logger.log(logger.EXTREME, "Leaving RensoNoteList.calculateHistory");
	}
	
	// 操作回数に重み付けする
	private void addWeight(HashMap<String, Integer> history, int weight){
		logger.log(logger.EXTREME, "Entering RensoNoteList.addWeight");
		
		Set<String> keySet = history.keySet();
		Iterator<String> hist_iterator = keySet.iterator();
		while(hist_iterator.hasNext()){
			String key = hist_iterator.next();
			history.put(key, history.get(key) * weight);
		}
		
		logger.log(logger.EXTREME, "Leaving RensoNoteList.addWeight");
	}
	
	// 連想ノートリストを再描画
	private void repaintRensoNoteList(boolean needClear) {
		logger.log(logger.EXTREME, "Entering RensoNoteList.repaintRensoNoteList");
		
		if (needClear) {
			this.clear();
			rensoNoteListItems.clear();
			rensoNoteListTrueItems.clear();
		}
		
		if (!this.isEnabled()) {
			return;
		}
		
		addRensoNoteList(mergedHistory);
		
		logger.log(logger.EXTREME, "Leaving RensoNoteList.repaintRensoNoteList");
	}
	
	// 引数1と引数2をマージしたハッシュマップを返す
	private HashMap<String, Integer> mergeHistory(HashMap<String, Integer> History1, HashMap<String, Integer> History2){
		logger.log(logger.EXTREME, "Entering RensoNoteList.mergeHistory");
		
		HashMap<String, Integer> mergedHistory = new HashMap<String, Integer>();
		
		mergedHistory.putAll(History1);
		
		Set<String> keySet = History2.keySet();
		Iterator<String> hist2_iterator = keySet.iterator();
		while(hist2_iterator.hasNext()){
			String key = hist2_iterator.next();
			if(mergedHistory.containsKey(key)){
				mergedHistory.put(key, mergedHistory.get(key) + History2.get(key));
			}else {
				mergedHistory.put(key, History2.get(key));
			}
		}
		
		logger.log(logger.EXTREME, "Leaving RensoNoteList.mergeHistory");
		return mergedHistory;
	}
	
	// 連想ノートリストにハッシュマップのデータを追加
	private void addRensoNoteList(HashMap<String, Integer> History){
		logger.log(logger.EXTREME, "Entering RensoNoteList.addRensoNoteList");
		
		enThumbnailRunner.setUser(Global.getUserInformation());
		enThumbnailRunner.setServerUrl(Global.getServer());
		
		String currentNoteGuid = new String(parent.getCurrentNoteGuid());
		
		// 除外されているノートを連想ノート候補から除去する
		Iterator<String> historyIterator = History.keySet().iterator();
		while (historyIterator.hasNext()) {
			if (conn.getExcludedTable().existNote(guid, historyIterator.next())) {
				historyIterator.remove();
			}
		}
		
		// すべての関連ポイントの合計を取得（関連度のパーセント算出に利用）
		int allPointSum = 0;
		for (int p : History.values()) {
			allPointSum += p;
		}
		
		// スター付きノートとスター無しノートを分ける
		HashMap<String, Integer> staredNotes = new HashMap<String, Integer>();	// スター付きノートのマップ
		HashMap<String, Integer> normalNotes = new HashMap<String, Integer>();	// スター無しノートのマップ
		for (String nextGuid: History.keySet()) {
			int relationPoint = History.get(nextGuid);
			boolean isStared = conn.getStaredTable().existNote(currentNoteGuid, nextGuid);
			if (isStared) {
				staredNotes.put(nextGuid, relationPoint);
			} else {
				normalNotes.put(nextGuid, relationPoint);
			}
		}
		
		// 連想ノートリストアイテムの最大表示数まで繰り返す
		for (int i = 0; i < Global.getRensoListItemMaximum(); i++) {
			// スター付きノートがあれば先に処理する
			HashMap<String, Integer> tmpMap = new HashMap<String, Integer>();
			if (!staredNotes.isEmpty()) {
				tmpMap = staredNotes;
			}else if (!normalNotes.isEmpty()) {
				tmpMap = normalNotes;
			}
			
			// 操作回数が多い順に取り出して連想ノートリストに追加する
			if (!tmpMap.isEmpty()) {
				int maxNum = -1;
				String maxGuid = new String();
				
				for (String nextGuid: tmpMap.keySet()) {
					int relationPoint = tmpMap.get(nextGuid);
					
					// 最大ノート探索する
					if (relationPoint > maxNum) {
						maxNum = relationPoint;
						maxGuid = nextGuid;
					}
				}
				
				// 次の最大値探索で邪魔なので最大値をHashMapから削除
				tmpMap.remove(maxGuid);
	
				// 関連度最大のノートがアクティブか確認
				Note maxNote = conn.getNoteTable().getNote(maxGuid, true, false, false, false, true);
				boolean isNoteActive = false;
				if(maxNote != null) {
					isNoteActive = maxNote.isActive();
				}
				
				// 存在していて、かつ関連度0でなければノート情報を取得して連想ノートリストに追加
				if (isNoteActive && maxNum > 0) {
					// Evernoteサムネイルが取得済みか確認。未取得ならサムネイル取得スレッドにキュー
					if (Global.isConnected) {
						String thumbnailName = Global.getFileManager().getResDirPath("enThumbnail-" + maxGuid + ".png");
						QFile thumbnail = new QFile(thumbnailName);
						if (!thumbnail.exists()) {	// Evernoteサムネイルがファイルとして存在しない
							QByteArray data = conn.getNoteTable().getENThumbnail(maxGuid);
							if (data == null) {	// Evernoteサムネイル未取得
								enThumbnailRunner.addGuid(maxGuid);
							}
						}
					}
					
					// スター付きか確認
					boolean isStared;
					isStared = conn.getStaredTable().existNote(currentNoteGuid, maxGuid);
					
					QListWidgetItem item = new QListWidgetItem();
					RensoNoteListItem myItem = new RensoNoteListItem(maxNote, maxNum, isStared, allPointSum, conn, this);
					item.setSizeHint(new QSize(0, 90));
					this.addItem(item);
					this.setItemWidget(item, myItem);
					rensoNoteListItems.put(item, maxGuid);
					rensoNoteListTrueItems.put(maxGuid, myItem);
				} else {
					break;
				}
			}
		}
		logger.log(logger.EXTREME, "Leaving RensoNoteList.addRensoNoteList");
	}

	// リストのアイテムから対象ノートのguidを取得
	public String getNoteGuid(QListWidgetItem item) {
		return rensoNoteListItems.get(item);
	}
	
	// 関連ノートリストの右クリックメニュー
	@Override
	public void contextMenuEvent(QContextMenuEvent event){
		logger.log(logger.EXTREME, "Entering RensoNoteList.contextMenuEvent");
		
		if (rensoNotePressedItemGuid == null || rensoNotePressedItemGuid.equals("")) {
			return;
		}
		
		// STAR, UNSTARがあれば、一度消す
		List<QAction> menuActions = new ArrayList<QAction>(menu.actions());
		if (menuActions.contains(starAction)) {
			menu.removeAction(starAction);
		}
		if (menuActions.contains(unstarAction)) {
			menu.removeAction(unstarAction);
		}
		
		// 対象アイテムがスター付きなら「UNSTAR」、スター無しなら「STAR」を追加
		String currentNoteGuid = parent.getCurrentNoteGuid();
		boolean isExist = conn.getStaredTable().existNote(currentNoteGuid, rensoNotePressedItemGuid);
		if (isExist) {
			menu.insertAction(excludeNoteAction, unstarAction);
		} else {
			menu.insertAction(excludeNoteAction, starAction);
		}
		
		// コンテキストメニューを表示
		menu.exec(event.globalPos());
		
		rensoNotePressedItemGuid = null;
		
		logger.log(logger.EXTREME, "Leaving RensoNoteList.contextMenuEvent");
	}
	
	// コンテキストメニューが表示されているかどうか
	public boolean isContextMenuVisible() {
		return menu.isVisible();
	}
	
	// コンテキストメニューが閉じられた時
	@SuppressWarnings("unused")
	private void contextMenuHidden() {
		for (RensoNoteListItem item : rensoNoteListTrueItems.values()) {
			item.setDefaultBackground();
		}
	}
	
	// ユーザが連想ノートリストのアイテムを選択した時の処理
	@SuppressWarnings("unused")
	private void rensoNoteItemPressed(QListWidgetItem current) {
		logger.log(logger.HIGH, "Entering RensoNoteList.rensoNoteItemPressed");
		
		rensoNotePressedItemGuid = null;
		// 右クリックだったときの処理
		if (QApplication.mouseButtons().isSet(MouseButton.RightButton)) {
			rensoNotePressedItemGuid = getNoteGuid(current);
		}
		
		logger.log(logger.HIGH, "Leaving RensoNoteList.rensoNoteItemPressed");
	}
	
	// Evernoteの関連ノートの取得が完了
	@SuppressWarnings("unused")
	private void enRelatedNotesComplete() {
		logger.log(logger.HIGH, "Entering RensoNoteList.enRelatedNotesComplete");
		
		Pair<String, List<String>> enRelatedNoteGuidPair = enRelatedNotesRunner.getENRelatedNoteGuids();	// <元ノートguid, 関連ノートguidリスト>
		
		if (enRelatedNoteGuidPair == null) {
			return;
		}
		
		String sourceGuid = enRelatedNoteGuidPair.getFirst();
		List<String> enRelatedNoteGuids = enRelatedNoteGuidPair.getSecond();
		
		
		if (sourceGuid != null && !sourceGuid.equals("") && enRelatedNoteGuids != null) {	// Evernote関連ノートがnullでなければ
			// まずキャッシュに追加
			enRelatedNotesCache.put(sourceGuid, enRelatedNoteGuids);
			
			if (!enRelatedNoteGuids.isEmpty()) {	// Evernote関連ノートが存在していて
				if (sourceGuid.equals(this.guid)) {	// 取得したデータが今開いているノートの関連ノートなら
					// mergedHistoryにEvernote関連ノートを追加してから再描画
					addENRelatedNotes(enRelatedNoteGuids);
					repaintRensoNoteList(true);
				}
			}
		}
		
		logger.log(logger.HIGH, "Leaving RensoNoteList.enRelatedNotesComplete");
	}
	
	// Evernote関連ノートの関連度情報をmergedHistoryに追加
	private void addENRelatedNotes(List<String> relatedNoteGuids) {
		logger.log(logger.EXTREME, "Entering RensoNoteList.addENRelatedNotes");
		
		// Evernote関連ノート<guid, 関連ポイント>
		HashMap<String, Integer> enRelatedNotes = new HashMap<String, Integer>();
		
		for (String relatedGuid : relatedNoteGuids) {
			enRelatedNotes.put(relatedGuid, Global.getENRelatedNotesWeight());
		}
		
		mergedHistory = mergeHistory(filterHistory(enRelatedNotes), mergedHistory);
		
		logger.log(logger.EXTREME, "Leaving RensoNoteList.addENRelatedNotes");
	}
	
	// Evernoteの関連ノート取得スレッドを終了させる
	public boolean stopThread() {
		logger.log(logger.HIGH, "Entering RensoNoteList.stopThread");
		
		if (!enRelatedNotesRunner.addStop()) {
			logger.log(logger.HIGH, "RensoNoteList.stopThread failed(enRelatedNotesRunner)");
			return false;
		}
		if (!enThumbnailRunner.addStop()) {
			logger.log(logger.HIGH, "RensoNoteList.stopThread failed(enThumbnailRunner)");
			return false;
		}
		
		logger.log(logger.HIGH, "RensoNoteList.stopThread succeeded");
		return true;
	}

	public QThread getEnRelatedNotesThread() {
		return enRelatedNotesThread;
	}
	
	public String getGuid() {
		return guid;
	}
	
	// ローカルに存在していて、かつアクティブなノートだけを返す
	private HashMap<String, Integer> filterHistory(HashMap<String, Integer> sourceHistory) {
		HashMap<String, Integer> dstHistory = new HashMap<String, Integer>();
		
		for (String guid : sourceHistory.keySet()) {
			if (conn.getNoteTable().exists(guid)) {
				if (conn.getNoteTable().getNote(guid, false, false, false, false, false).isActive()) {
					dstHistory.put(guid, sourceHistory.get(guid));
				}
			}
		}
		
		return dstHistory;
	}
	
	/**
	 * Evernoteサムネイルの取得が完了
	 * 
	 * @param guid 現在開いているノートのguid
	 */
	@SuppressWarnings("unused")
	private void enThumbnailComplete(String guid) {
		logger.log(logger.HIGH, "Entering RensoNoteList.enThumbnailComplete");
		
		for (Map.Entry<String, RensoNoteListItem> e : rensoNoteListTrueItems.entrySet()) {
			// サムネイル取得が完了したノートが現在の連想ノートリストに表示されていたら再描画
			if (guid.equals(e.getKey())) {
				e.getValue().repaint();
			}
		}
		
		logger.log(logger.HIGH, "Leaving RensoNoteList.enThumbnailComplete");
	}
}
