package org.droidplanner.android.tlog.viewers

import android.content.Intent
import android.os.Bundle
import android.support.design.widget.FloatingActionButton
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import com.MAVLink.common.msg_global_position_int
import com.o3dr.android.client.utils.data.tlog.TLogParser
import com.o3dr.services.android.lib.coordinate.LatLong
import com.o3dr.services.android.lib.coordinate.LatLongAlt
import com.o3dr.services.android.lib.drone.mission.Mission
import com.o3dr.services.android.lib.drone.mission.item.spatial.SplineWaypoint
import com.o3dr.services.android.lib.util.MathUtils
import org.droidplanner.android.R
import org.droidplanner.android.activities.EditorActivity
import org.droidplanner.android.droneshare.data.SessionContract
import org.droidplanner.android.tlog.adapters.TLogPositionEventAdapter
import org.droidplanner.android.tlog.event.TLogEventDetail
import org.droidplanner.android.tlog.event.TLogEventListener
import org.droidplanner.android.tlog.event.TLogEventMapFragment
import org.droidplanner.android.view.FastScroller

/**
 * @author ne0fhyk (Fredia Huya-Kouadio)
 */
class TLogPositionViewer : TLogViewer(), TLogEventListener {

    companion object {
        fun msg_global_position_intToLatLongAlt(position : msg_global_position_int): LatLongAlt {
            return LatLongAlt(position.lat.toDouble()/ 1E7, position.lon.toDouble()/ 1E7, (position.relative_alt / 1000.0))
        }
    }

    private var tlogPositionAdapter : TLogPositionEventAdapter? = null

    private val noDataView by lazy {
        getView()?.findViewById(R.id.no_data_message)
    }

    private val loadingData by lazy {
        getView()?.findViewById(R.id.loading_tlog_data)
    }

    private val eventsView by lazy {
        getView()?.findViewById(R.id.event_list) as RecyclerView?
    }

    private val fastScroller by lazy {
        getView()?.findViewById(R.id.fast_scroller) as FastScroller
    }

    private val newPositionEvents = mutableListOf<TLogParser.Event>()

    private var tlogEventMap : TLogEventMapFragment? = null
    private var tlogEventDetail : TLogEventDetail? = null

    private var lastEventTimestamp = -1L
    private var toleranceInPixels = 0.0

    private var missionExportMenuItem: MenuItem? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View?{
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_tlog_position_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?){
        super.onViewCreated(view, savedInstanceState)

        toleranceInPixels = scaleDpToPixels(15.0).toDouble()

        val fm = childFragmentManager
        tlogEventMap = fm.findFragmentById(R.id.tlog_map_container) as TLogEventMapFragment?
        if(tlogEventMap == null){
            tlogEventMap = TLogEventMapFragment()
            fm.beginTransaction().add(R.id.tlog_map_container, tlogEventMap).commit()
        }

        tlogEventDetail = fm.findFragmentById(R.id.tlog_event_detail) as TLogEventDetail?
        if(tlogEventDetail == null){
            tlogEventDetail = TLogEventDetail()
            fm.beginTransaction().add(R.id.tlog_event_detail, tlogEventDetail).commit()
        }

        eventsView?.apply {
            setHasFixedSize(true)
            layoutManager = LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false)
        }

        tlogPositionAdapter = TLogPositionEventAdapter(eventsView!!)
        eventsView?.adapter = tlogPositionAdapter

        fastScroller.setRecyclerView(eventsView!!)
        tlogPositionAdapter?.setTLogEventClickListener(this)

        val goToMyLocation = view.findViewById(R.id.my_location_button) as FloatingActionButton
        goToMyLocation.setOnClickListener {
            tlogEventMap?.goToMyLocation();
        }

        val goToDroneLocation = view.findViewById(R.id.drone_location_button) as FloatingActionButton
        goToDroneLocation.setOnClickListener {
            tlogEventMap?.goToDroneLocation()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater){
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_tlog_position_viewer, menu)
        missionExportMenuItem = menu.findItem(R.id.menu_export_mission)
    }

    override fun onOptionsItemSelected(item : MenuItem): Boolean {
        when(item.itemId){
            R.id.menu_export_mission -> {
                // Generate a mission from the drone historical gps position.
                val events = tlogPositionAdapter?.getItems() ?: return true
                val positions = mutableListOf<LatLong>()
                for(event in events){
                    positions.add(msg_global_position_intToLatLongAlt(event!!.mavLinkMessage as msg_global_position_int))
                }

                // Simplify the generated path
                val simplifiedPath = MathUtils.simplify(positions, toleranceInPixels)
                val mission = Mission()
                for(point in simplifiedPath){
                    val missionItem = SplineWaypoint()
                    missionItem.coordinate = point as LatLongAlt
                    mission.addMissionItem(missionItem)
                }

                startActivity(Intent(activity, EditorActivity::class.java)
                        .setAction(EditorActivity.ACTION_VIEW_MISSION)
                        .putExtra(EditorActivity.EXTRA_MISSION, mission))
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun scaleDpToPixels(value: Double): Int {
        val scale = resources.displayMetrics.density
        return Math.round(value * scale).toInt()
    }

    override fun onTLogSelected(tlogSession: SessionContract.SessionData) {
        tlogPositionAdapter?.clear()
        lastEventTimestamp = -1L
        stateLoadingData()

        // Refresh the map.
        tlogEventMap?.onTLogSelected(tlogSession)
        tlogEventDetail?.onTLogEventSelected(null)
    }

    override fun onTLogDataLoaded(events: List<TLogParser.Event>, hasMore: Boolean) {
        // Parse the event list and retrieve only the position events.
        newPositionEvents.clear()

        for(event in events){
            if(event.mavLinkMessage is msg_global_position_int) {
                // Events should be at least 1 second apart.
                if(lastEventTimestamp == -1L || (event.timestamp/1000 - lastEventTimestamp/1000) >= 1L){
                    lastEventTimestamp = event.timestamp
                    newPositionEvents.add(event)
                }
            }
        }

        // Refresh the adapter
        tlogPositionAdapter?.addItems(newPositionEvents)
        tlogPositionAdapter?.setHasMoreData(hasMore)

        if(tlogPositionAdapter?.itemCount == 0){
            if(hasMore){
                stateLoadingData()
            }
            else {
                stateNoData()
            }
        } else {
            stateDataLoaded()
        }

        tlogEventMap?.onTLogDataLoaded(newPositionEvents, hasMore)
    }

    override fun onTLogEventSelected(event: TLogParser.Event?) {
        // Show the detail window for this event
        tlogEventDetail?.onTLogEventSelected(event)

        //Propagate the click event to the map
        tlogEventMap?.onTLogEventSelected(event)
    }

    private fun stateLoadingData() {
        noDataView?.visibility = View.GONE
        eventsView?.visibility = View.GONE
        fastScroller.visibility = View.GONE
        loadingData?.visibility = View.VISIBLE

        missionExportMenuItem?.apply {
            setVisible(false)
            setEnabled(false)
        }
    }

    private fun stateNoData(){
        noDataView?.visibility = View.VISIBLE
        eventsView?.visibility = View.GONE
        fastScroller.visibility = View.GONE
        loadingData?.visibility = View.GONE

        missionExportMenuItem?.apply {
            setVisible(false)
            setEnabled(false)
        }
    }

    private fun stateDataLoaded(){
        noDataView?.visibility = View.GONE
        eventsView?.visibility = View.VISIBLE
        fastScroller.visibility = View.VISIBLE
        loadingData?.visibility = View.GONE

        missionExportMenuItem?.apply {
            setVisible(true)
            setEnabled(true)
        }
    }
}
