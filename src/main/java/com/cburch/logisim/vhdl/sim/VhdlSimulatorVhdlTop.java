/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.vhdl.sim;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.std.hdl.VhdlEntityComponent;
import com.cburch.logisim.util.FileUtil;
import com.cburch.logisim.util.LocaleManager;
import com.cburch.logisim.vhdl.base.VhdlEntity;
import com.cburch.logisim.vhdl.base.VhdlEntityAttributes;
import com.cburch.logisim.vhdl.base.VhdlParser;
import com.cburch.logisim.vhdl.base.VhdlSimConstants;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates a simulation top file. This file contains all the interfaces to the entities (in and
 * out pins) so the simulation is run on a single top component. It allows us to have only one
 * instance of Questasim running.
 *
 * @author christian.mueller@heig-vd.ch
 */
public class VhdlSimulatorVhdlTop {
  static final Logger logger = LoggerFactory.getLogger(VhdlSimulatorVhdlTop.class);
  private boolean valid = false;
  private final VhdlSimulatorTop vhdlSimulator;
  private boolean firstPort;
  private boolean firstComp;
  private boolean firstMap;

  VhdlSimulatorVhdlTop(VhdlSimulatorTop vs) {
    vhdlSimulator = vs;
  }

  public void fireInvalidated() {
    valid = false;
  }

  public void generate(List<Component> comps) {
    /* Do not generate if file is already valid */
    if (valid) return;

    final var lineSeparator = System.getProperty("line.separator");

    final var ports = new StringBuilder();
    ports.append(String.format("Autogenerated by %s --", BuildInfo.displayName));
    ports.append(lineSeparator);

    final var components = new StringBuilder();
    components.append(String.format("Autogenerated by %s --", BuildInfo.displayName));
    components.append(lineSeparator);

    final var map = new StringBuilder();
    map.append(String.format("Autogenerated by %s --", BuildInfo.displayName));
    map.append(lineSeparator);

    firstPort = firstComp = firstMap = true;
    String[] type = {Port.INOUT, Port.INPUT, Port.OUTPUT};

    /* For each vhdl entity */
    for (final var comp : comps) {
      final var state = vhdlSimulator.getProject().getCircuitState().getInstanceState(comp);

      final var fac = comp.getFactory();
      String vhdlEntityName;
      final var myPorts = new ArrayList<VhdlParser.PortDescription>();
      if (fac instanceof VhdlEntity) {
        vhdlEntityName = ((VhdlEntity) fac).getSimName(state.getInstance().getAttributeSet());
        myPorts.addAll(((VhdlEntityAttributes) state.getAttributeSet()).getContent().getPorts());
      } else {
        vhdlEntityName =
            ((VhdlEntityComponent) fac).getSimName(state.getInstance().getAttributeSet());
        for (final var port :
            state.getAttributeValue(VhdlEntityComponent.CONTENT_ATTR).getPorts()) {
          VhdlParser.PortDescription nport =
              new VhdlParser.PortDescription(
                  port.getToolTip(), type[port.getType()], port.getFixedBitWidth().getWidth());
          myPorts.add(nport);
        }
      }

      /*
       * Create ports
       */
      for (final var port : myPorts) {
        if (!firstPort) {
          ports.append(";");
          ports.append(lineSeparator);
        } else {
          firstPort = false;
        }
        final var portName = vhdlEntityName + "_" + port.getName();
        ports
            .append("      ")
            .append(portName)
            .append(" : ")
            .append(port.getVhdlType())
            .append(" std_logic");
        int width = port.getWidth().getWidth();
        if (width > 1) {
          ports.append("_vector(").append(width - 1).append(" downto 0)");
        }
      }

      /*
       * Create components
       */
      components.append("   component ").append(vhdlEntityName);
      components.append(lineSeparator);

      components.append("      port (");
      components.append(lineSeparator);

      firstComp = true;
      for (final var port : myPorts) {
        if (!firstComp) {
          components.append(";");
          components.append(lineSeparator);
        } else {
          firstComp = false;
        }

        components
            .append("         ")
            .append(port.getName())
            .append(" : ")
            .append(port.getVhdlType())
            .append(" std_logic");

        int width = port.getWidth().getWidth();
        if (width > 1) {
          components.append("_vector(").append(width - 1).append(" downto 0)");
        }
      }

      components.append(lineSeparator);
      components.append("      );");
      components.append(lineSeparator);

      components.append("   end component ;");
      components.append(lineSeparator);

      components.append("   ");
      components.append(lineSeparator);

      /*
       * Create port map
       */
      map.append("   ")
          .append(vhdlEntityName)
          .append("_map : ")
          .append(vhdlEntityName)
          .append(" port map (");
      map.append(lineSeparator);

      firstMap = true;
      for (final var port : myPorts) {

        if (!firstMap) {
          map.append(",");
          map.append(lineSeparator);
        } else {
          firstMap = false;
        }

        map.append("      ")
            .append(port.getName())
            .append(" => ")
            .append(vhdlEntityName)
            .append("_")
            .append(port.getName());
      }
      map.append(lineSeparator);
      map.append("   );");
      map.append(lineSeparator);
      map.append("   ");
      map.append(lineSeparator);
    }

    ports.append(lineSeparator);
    ports.append("      ---------------------------");
    ports.append(lineSeparator);

    components.append("   ---------------------------");
    components.append(lineSeparator);

    map.append("   ---------------------------");
    map.append(lineSeparator);

    /*
     * Replace template blocks by generated datas
     */
    String template;
    try {
      template =
          new String(
              FileUtil.getBytes(
                  this.getClass()
                      .getResourceAsStream(
                          VhdlSimConstants.VHDL_TEMPLATES_PATH + "top_sim.templ")));
    } catch (IOException e) {
      logger.error("Could not read template : {}", e.getMessage());
      return;
    }

    template = template
            .replaceAll("%date%", LocaleManager.PARSER_SDF.format(new Date()))
            .replaceAll("%ports%", ports.toString())
            .replaceAll("%components%", components.toString())
            .replaceAll("%map%", map.toString());

    PrintWriter writer;
    try {
      writer = new PrintWriter(
              VhdlSimConstants.SIM_SRC_PATH + VhdlSimConstants.SIM_TOP_FILENAME,
              StandardCharsets.UTF_8);
      writer.print(template);
      writer.close();
    } catch (IOException e) {
      logger.error("Could not create top_sim file : {}", e.getMessage());
      e.printStackTrace();
      return;
    }

    valid = true;
  }
}
