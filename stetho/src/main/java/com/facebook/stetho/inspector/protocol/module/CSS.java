/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.stetho.inspector.protocol.module;

import com.facebook.stetho.common.ListUtil;
import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.common.Util;
import com.facebook.stetho.inspector.elements.ComputedStyleAccumulator;
import com.facebook.stetho.inspector.elements.Document;
import com.facebook.stetho.inspector.elements.Origin;
import com.facebook.stetho.inspector.elements.StyleAccumulator;
import com.facebook.stetho.inspector.helper.ChromePeerManager;
import com.facebook.stetho.inspector.helper.PeersRegisteredListener;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcResult;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsDomain;
import com.facebook.stetho.inspector.protocol.ChromeDevtoolsMethod;
import com.facebook.stetho.json.ObjectMapper;
import com.facebook.stetho.json.annotation.JsonProperty;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CSS implements ChromeDevtoolsDomain {
  private final ChromePeerManager mPeerManager;
  private final Document mDocument;
  private final ObjectMapper mObjectMapper;

  /**
   * This should not be used outside of getMatchedStylesForNode to collect styles mapped to a
   * selector name. The reason it is a member is to avoid allocations on every call to that method.
   * The correct usage of this field is it clear the mapped array lists ensuring they are empty
   * after usage. Do not attempt to use this from any other method or especially thread.
   */
  private final Map<String, ArrayList<CSSProperty>> mSelectorCollector = new HashMap<>();

  public CSS(Document document) {
    mDocument = Util.throwIfNull(document);
    mObjectMapper = new ObjectMapper();
    mPeerManager = new ChromePeerManager();
    mPeerManager.setListener(new PeerManagerListener());
  }

  @ChromeDevtoolsMethod
  public void enable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public void disable(JsonRpcPeer peer, JSONObject params) {
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getComputedStyleForNode(JsonRpcPeer peer, JSONObject params) {
    final GetComputedStyleForNodeRequest request = mObjectMapper.convertValue(
        params,
        GetComputedStyleForNodeRequest.class);

    final GetComputedStyleForNodeResult result = new GetComputedStyleForNodeResult();
    result.computedStyle = new ArrayList<>();

    mDocument.postAndWait(new Runnable() {
      @Override
      public void run() {
        Object element = mDocument.getElementForNodeId(request.nodeId);

        if (element == null) {
          LogUtil.e("Tried to get the style of an element that does not exist, using nodeid=" +
              request.nodeId);

          return;
        }

        mDocument.getElementComputedStyles(
            element,
            new ComputedStyleAccumulator() {
              @Override
              public void store(String name, String value) {
                final CSSComputedStyleProperty property = new CSSComputedStyleProperty();
                property.name = name;
                property.value = value;
                result.computedStyle.add(property);
              }
            });
      }
    });

    return result;
  }

  @ChromeDevtoolsMethod
  public JsonRpcResult getMatchedStylesForNode(JsonRpcPeer peer, JSONObject params) {
    final GetMatchedStylesForNodeRequest request = mObjectMapper.convertValue(
        params,
        GetMatchedStylesForNodeRequest.class);

    mDocument.postAndWait(new Runnable() {
      @Override
      public void run() {
        Object elementForNodeId = mDocument.getElementForNodeId(request.nodeId);

        if (elementForNodeId == null) {
          LogUtil.w("Failed to get style of an element that does not exist, nodeid=" +
              request.nodeId);
          return;
        }

        mDocument.getElementStyles(
            elementForNodeId,
            new StyleAccumulator() {
              @Override
              public void store(String selector, String name, String value, boolean isDefault) {
                if (!isDefault) {
                  ArrayList<CSSProperty> properties = mSelectorCollector.get(selector);
                  if (properties == null) {
                    properties = new ArrayList<>();
                    mSelectorCollector.put(selector, properties);
                  }

                  final CSSProperty property = new CSSProperty();
                  property.name = name;
                  property.value = value;
                  properties.add(property);
                }
              }
            });
      }
    });

    final GetMatchedStylesForNodeResult result = new GetMatchedStylesForNodeResult();
    result.matchedCSSRules = new ArrayList<>();
    result.inherited = Collections.emptyList();
    result.pseudoElements = Collections.emptyList();

    for (Map.Entry<String, ArrayList<CSSProperty>> entry : mSelectorCollector.entrySet()) {
      final ArrayList<CSSProperty> properties = entry.getValue();
      if (properties.isEmpty()) {
        continue;
      }

      final RuleMatch match = new RuleMatch();
      match.matchingSelectors = ListUtil.newImmutableList(0);

      final Selector selector = new Selector();
      selector.value = entry.getKey();

      final CSSRule rule = new CSSRule();
      rule.origin = Origin.REGULAR;
      rule.selectorList = new SelectorList();
      rule.selectorList.selectors = ListUtil.newImmutableList(selector);
      rule.style = new CSSStyle();
      rule.style.cssProperties = ListUtil.copyToImmutableList(properties);
      rule.style.shorthandEntries = Collections.emptyList();

      match.rule = rule;
      result.matchedCSSRules.add(match);

      properties.clear();
    }

    return result;
  }

  private final class PeerManagerListener extends PeersRegisteredListener {
    @Override
    protected synchronized void onFirstPeerRegistered() {
      mDocument.addRef();
    }

    @Override
    protected synchronized void onLastPeerUnregistered() {
      mDocument.release();
    }
  }

  private static class CSSComputedStyleProperty {
    @JsonProperty(required = true)
    public String name;

    @JsonProperty(required = true)
    public String value;
  }

  private static class RuleMatch {
    @JsonProperty
    public CSSRule rule;

    @JsonProperty
    public List<Integer> matchingSelectors;
  }

  private static class SelectorList {
    @JsonProperty
    public List<Selector> selectors;

    @JsonProperty
    public String text;
  }

  private static class SourceRange {
    @JsonProperty(required = true)
    public int startLine;

    @JsonProperty(required = true)
    public int startColumn;

    @JsonProperty(required = true)
    public int endLine;

    @JsonProperty(required = true)
    public int endColumn;
  }

  private static class Selector {
    @JsonProperty(required = true)
    public String value;

    @JsonProperty
    public SourceRange range;
  }

  private static class CSSRule {
    @JsonProperty
    public String styleSheetId;

    @JsonProperty(required = true)
    public SelectorList selectorList;

    @JsonProperty
    public Origin origin;

    @JsonProperty
    public CSSStyle style;
  }

  private static class CSSStyle {
    @JsonProperty
    public String styleSheetId;

    @JsonProperty(required = true)
    public List<CSSProperty> cssProperties;

    @JsonProperty
    public List<ShorthandEntry> shorthandEntries;

    @JsonProperty
    public String cssText;

    @JsonProperty
    public SourceRange range;
  }

  private static class ShorthandEntry {
    @JsonProperty(required = true)
    public String name;

    @JsonProperty(required = true)
    public String value;

    @JsonProperty
    public Boolean imporant;
  }

  private static class CSSProperty {
    @JsonProperty(required = true)
    public String name;

    @JsonProperty(required = true)
    public String value;

    @JsonProperty
    public Boolean important;

    @JsonProperty
    public Boolean implicit;

    @JsonProperty
    public String text;

    @JsonProperty
    public Boolean parsedOk;

    @JsonProperty
    public Boolean disabled;

    @JsonProperty
    public SourceRange range;
  }

  private static class PseudoIdMatches {
    @JsonProperty(required = true)
    public int pseudoId;

    @JsonProperty(required = true)
    public List<RuleMatch> matches;

    public PseudoIdMatches() {
      this.matches = new ArrayList<>();
    }
  }

  private static class GetComputedStyleForNodeRequest {
    @JsonProperty(required = true)
    public int nodeId;
  }

  private static class InheritedStyleEntry {
    @JsonProperty(required = true)
    public CSSStyle inlineStyle;

    @JsonProperty(required = true)
    public List<RuleMatch> matchedCSSRules;
  }

  private static class GetComputedStyleForNodeResult implements JsonRpcResult {
    @JsonProperty(required = true)
    public List<CSSComputedStyleProperty> computedStyle;
  }

  private static class GetMatchedStylesForNodeRequest implements JsonRpcResult {
    @JsonProperty(required = true)
    public int nodeId;

    @JsonProperty
    public Boolean excludePseudo;

    @JsonProperty
    public Boolean excludeInherited;
  }

  private static class GetMatchedStylesForNodeResult implements JsonRpcResult {
    @JsonProperty
    public List<RuleMatch> matchedCSSRules;

    @JsonProperty
    public List<PseudoIdMatches> pseudoElements;

    @JsonProperty
    public List<InheritedStyleEntry> inherited;
  }
}
